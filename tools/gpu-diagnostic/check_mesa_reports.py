"""Require the Mesa shader, GPU agreement, and reference-policy cases in JUnit XML."""
import argparse
import gzip
import hashlib
import json
import math
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zlib

PREFIX = 'dev.cinematic.p0.gpu.mesa.'
GOLDEN_CASE = (PREFIX + 'MesaGoldenHarnessTest', 'captureGoldenMatrixOnMesa')
GOLDEN_IDS = {f'c{index:03d}' for index in range(1, 91)}
SHA256 = re.compile(r'[0-9a-fA-F]{64}')
REQUIRED = {
    PREFIX + 'MesaShaderAssetsTest': {'everyProductAssetCompilesAndLinksInGles32'},
    PREFIX + 'MesaImageAgreementTest': {
        f'{method}[{size}]'
        for method in ('rgba32fCopyPreservesEverySyntheticPixel', 'realColorAssetReportsUnchangedKotlinReference')
        for size in ('64x48', '256x192', '1600x1200')
    },
    PREFIX + 'MesaExportBandsTest': {
        'fieldsHaveNo64Or256RowSeamsAndReportKotlinReference',
        'structuredFieldsPreserveFullFrameOrderAndReportErrorDistribution',
    },
    PREFIX + 'MesaReferencePolicyTest': {
        'largeCpuDifferencesRemainReferenceOnly',
        'referenceReportingStillRejectsNonFiniteAndMalformedInputs',
    },
}


def check_reports(directory):
    totals, seconds, _ = _scan_reports(directory)
    return totals, seconds


def _scan_reports(directory):
    reports = sorted(Path(directory).glob('TEST-*.xml'))
    if not reports:
        raise ValueError('Mesa tests did not produce JUnit reports')
    totals = dict.fromkeys(('tests', 'failures', 'errors', 'skipped'), 0)
    observed = set()
    seconds = 0.0
    for path in reports:
        suite = ET.parse(path).getroot()
        if suite.tag != 'testsuite':
            raise ValueError(f'Expected one JUnit testsuite: {path.name}')
        cases = suite.findall('testcase')
        counts = {key: int(suite.attrib[key]) for key in totals}
        if counts['tests'] != len(cases) or counts['tests'] <= 0:
            raise ValueError(f'Empty or incomplete JUnit suite: {path.name}')
        if any(counts[key] != 0 for key in ('failures', 'errors', 'skipped')):
            raise ValueError(f'Mesa suite did not pass completely: {path.name} {counts}')
        for case in cases:
            key = (case.attrib['classname'], case.attrib['name'])
            if not key[0].startswith(PREFIX) or key in observed:
                raise ValueError(f'Unexpected or duplicate Mesa case: {key}')
            if any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped')):
                raise ValueError(f'Mesa case did not pass: {key}')
            observed.add(key)
        elapsed = float(suite.attrib['time'])
        if not math.isfinite(elapsed) or elapsed < 0:
            raise ValueError(f'Invalid JUnit time: {path.name}')
        seconds += elapsed
        for key in totals:
            totals[key] += counts[key]
    required = {(suite, case) for suite, cases in REQUIRED.items() for case in cases}
    missing = required - observed
    if missing:
        raise ValueError(f'Missing required Mesa cases: {sorted(missing)}')
    return totals, seconds, observed


def check_golden(candidate_dir, observed, *, manifest_path=None):
    if GOLDEN_CASE not in observed:
        raise ValueError(f'Missing passing Mesa harness case: {GOLDEN_CASE}')
    directory = Path(candidate_dir)
    capture = json.loads((directory / 'capture.json').read_text(encoding='utf-8'))
    if not isinstance(capture, dict):
        raise ValueError('Expected a golden capture object')
    status = capture.get('status')
    if status not in ('complete', 'partial'):
        raise ValueError(f'Invalid golden status: {status}')
    cases = capture.get('cases')
    if not isinstance(cases, list) or not cases:
        raise ValueError('Expected at least one golden case')

    case_count = capture.get('caseCount')
    if type(case_count) is not int or case_count != len(cases):
        raise ValueError('Golden caseCount mismatch')

    ids = set()
    for case in cases:
        if not isinstance(case, dict):
            raise ValueError('Expected a golden case object')
        case_id = case.get('id')
        if not isinstance(case_id, str) or case_id not in GOLDEN_IDS:
            raise ValueError(f'Unexpected golden ID: {case_id}')
        if case_id in ids:
            raise ValueError(f'Duplicate golden ID: {case_id}')
        ids.add(case_id)
    if status == 'complete' and ids != GOLDEN_IDS:
        raise ValueError(f'Missing golden IDs: {sorted(GOLDEN_IDS - ids)}')

    for case in cases:
        case_id = case['id']
        name = case.get('file')
        if not isinstance(name, str) or Path(name).name != name or name in ('', '.', '..'):
            raise ValueError(f'{case_id}: invalid file name')
        for field in ('rgbSha256', 'fileSha256'):
            digest = case.get(field)
            if not isinstance(digest, str) or not SHA256.fullmatch(digest):
                raise ValueError(f'{case_id}: invalid {field}')
        size = case.get('compressedBytes')
        if type(size) is not int or size <= 0:
            raise ValueError(f'{case_id}: invalid compressedBytes')
        file = directory / name
        if not file.is_file():
            raise ValueError(f'{case_id}: missing image file {name}')
        digest = hashlib.sha256(file.read_bytes()).hexdigest()
        if digest != case['fileSha256'].lower():
            raise ValueError(f'{case_id}: fileSha256 mismatch')

        width, height = case.get('width'), case.get('height')
        if type(width) is not int or type(height) is not int or width <= 0 or height <= 0:
            raise ValueError(f'{case_id}: invalid image dimensions')
        expected_rgb_bytes = width * height * 3
        rgb_bytes = 0
        rgb_digest = hashlib.sha256()
        try:
            with gzip.open(file, 'rb') as image:
                while chunk := image.read(64 * 1024):
                    rgb_bytes += len(chunk)
                    if rgb_bytes > expected_rgb_bytes:
                        break
                    rgb_digest.update(chunk)
        except (OSError, EOFError, zlib.error) as error:
            raise ValueError(f'{case_id}: invalid gzip image') from error
        if rgb_bytes != expected_rgb_bytes:
            raise ValueError(f'{case_id}: decompressed RGB size mismatch')
        if rgb_digest.hexdigest() != case['rgbSha256'].lower():
            raise ValueError(f'{case_id}: rgbSha256 mismatch')

        index = int(case_id[1:])
        group = 'small' if index <= 58 else 'preview' if index <= 76 else 'export'
        if case.get('group') != group:
            raise ValueError(f'{case_id}: group mismatch, expected {group}')
        expected_rows = [64, 256] if group == 'export' else []
        if case.get('bandRowsPassed') != expected_rows:
            raise ValueError(f'{case_id}: invalid bandRowsPassed')
        for rows in expected_rows:
            band = case.get(f'bands{rows}')
            if not isinstance(band, dict) or not {'changedPixels', 'maxDelta', 'firstChangedXY'} <= band.keys():
                raise ValueError(f'{case_id}: missing bands{rows} record or fields')

    host = directory / 'host.json'
    if not host.is_file():
        raise ValueError('Missing golden host.json')
    host_data = json.loads(host.read_text(encoding='utf-8'))
    cpu = host_data.get('cpu') if isinstance(host_data, dict) else None
    if not isinstance(cpu, dict) or not isinstance(cpu.get('modelName'), str):
        raise ValueError('Golden host.json requires a cpu object with a modelName string')
    run_id = capture.get('runId')
    host_run_id = host_data.get('runId')
    if not isinstance(run_id, str) or not isinstance(host_run_id, str) or run_id != host_run_id:
        raise ValueError('Golden capture and host runId must be matching strings')
    base_libraries = host_data.get('baseLibraries')
    if not isinstance(base_libraries, dict):
        raise ValueError('Golden host.json requires a baseLibraries object')
    if manifest_path is None:
        manifest_path = Path(__file__).resolve().parent.parent / 'gpu-mesa' / 'mesa-debs-manifest.txt'
    try:
        manifest_lines = Path(manifest_path).read_text(encoding='utf-8').splitlines()
    except (OSError, UnicodeError) as error:
        raise ValueError(f'Cannot read Mesa deb manifest: {manifest_path}') from error
    expected_libraries = set()
    for line in manifest_lines:
        line = line.strip()
        if line.startswith('#'):
            continue
        fields = line.split(' | ')
        if len(fields) == 7 and fields[5:] == ['B', 'yes'] and fields[1].strip():
            expected_libraries.add(fields[1])
    if not expected_libraries:
        raise ValueError('Mesa deb manifest has no category B installation targets')
    missing = sorted(expected_libraries - base_libraries.keys())
    extra = sorted(base_libraries.keys() - expected_libraries)
    if missing or extra:
        raise ValueError(f'Golden host.json baseLibraries mismatch: missing={missing}, extra={extra}')
    for name, library in base_libraries.items():
        if not isinstance(library, dict):
            raise ValueError(f'Golden host.json baseLibraries entry {name} must be an object')
        version = library.get('version')
        if not isinstance(version, str) or not version:
            raise ValueError(f'Golden host.json baseLibraries entry {name} requires a non-empty version string')
        if library.get('source') not in ('runner', 'stored'):
            raise ValueError(f'Golden host.json baseLibraries entry {name} requires a source of runner or stored')
    if 'llvmpipe' in host_data:
        llvmpipe = host_data['llvmpipe']
        if not isinstance(llvmpipe, dict) or any(
            value is not None and not isinstance(value, str) for value in llvmpipe.values()
        ):
            raise ValueError('Golden host.json llvmpipe must be an object with string or null values')
    print(f'GPU_MESA_GOLDEN cases={len(cases)} status={status}', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--golden-dir', type=Path)
    args = parser.parse_args()
    totals, seconds, observed = _scan_reports(args.directory)
    if args.golden_dir is not None:
        check_golden(args.golden_dir, observed)
    print(f'GPU_MESA_JUNIT {totals} seconds={seconds:.3f} requiredCases={sum(map(len, REQUIRED.values()))}', flush=True)
