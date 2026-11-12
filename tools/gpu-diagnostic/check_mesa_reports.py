"""Require the Mesa shader, GPU agreement, and reference-policy cases in JUnit XML."""
from pathlib import Path
import math
import sys
import xml.etree.ElementTree as ET

PREFIX = 'dev.cinematic.p0.gpu.mesa.'
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
    return totals, seconds


if __name__ == '__main__':
    totals, seconds = check_reports(sys.argv[1])
    print(f'GPU_MESA_JUNIT {totals} seconds={seconds:.3f} requiredCases={sum(map(len, REQUIRED.values()))}', flush=True)
