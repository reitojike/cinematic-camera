"""Require the Mesa shader, GPU agreement, and reference-policy cases in JUnit XML."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import xml.etree.ElementTree as ET

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


def check_golden(candidate_dir, observed):
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

        group = case.get('group')
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
