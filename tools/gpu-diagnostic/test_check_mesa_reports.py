import gzip
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET
from check_mesa_reports import GOLDEN_CASE, REQUIRED, check_golden, check_reports


class MesaReportsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        for index, (classname, names) in enumerate(REQUIRED.items()):
            root = ET.Element('testsuite', name=classname, tests=str(len(names)),
                              failures='0', errors='0', skipped='0', time='1.5')
            for name in sorted(names):
                ET.SubElement(root, 'testcase', classname=classname, name=name, time='0.1')
            ET.ElementTree(root).write(self.directory / f'TEST-{index}.xml')

    def mutate(self, change):
        path = self.directory / 'TEST-1.xml'
        tree = ET.parse(path)
        change(tree.getroot())
        tree.write(path)

    def test_complete_catalog_passes(self):
        totals, seconds = check_reports(self.directory)
        self.assertEqual(11, totals['tests'])
        self.assertEqual(6.0, seconds)

    def test_catalog_with_harness_passes(self):
        classname, name = GOLDEN_CASE
        root = ET.Element('testsuite', name=classname, tests='1',
                          failures='0', errors='0', skipped='0', time='1')
        ET.SubElement(root, 'testcase', classname=classname, name=name, time='1')
        ET.ElementTree(root).write(self.directory / 'TEST-golden.xml')
        totals, seconds = check_reports(self.directory)
        self.assertEqual(12, totals['tests'])
        self.assertEqual(7.0, seconds)

    def test_missing_suite_fails(self):
        (self.directory / 'TEST-0.xml').unlink()
        with self.assertRaisesRegex(ValueError, 'Missing required'):
            check_reports(self.directory)

    def test_missing_parameter_case_fails(self):
        def remove(root):
            root.remove(root.find('testcase'))
            root.set('tests', str(len(root.findall('testcase'))))
        self.mutate(remove)
        with self.assertRaisesRegex(ValueError, 'Missing required'):
            check_reports(self.directory)

    def test_suite_failure_error_or_skip_fails(self):
        for key in ('failures', 'errors', 'skipped'):
            with self.subTest(key=key):
                self.mutate(lambda root: root.set(key, '1'))
                with self.assertRaisesRegex(ValueError, 'did not pass completely'):
                    check_reports(self.directory)
                self.mutate(lambda root: root.set(key, '0'))

    def test_case_skip_cannot_hide_behind_zero_summary(self):
        self.mutate(lambda root: ET.SubElement(root.find('testcase'), 'skipped'))
        with self.assertRaisesRegex(ValueError, 'case did not pass'):
            check_reports(self.directory)

    def test_count_mismatch_and_zero_fail(self):
        for count in ('0', '100'):
            self.mutate(lambda root: root.set('tests', count))
            with self.assertRaisesRegex(ValueError, 'Empty or incomplete'):
                check_reports(self.directory)

    def test_duplicate_case_fails(self):
        def duplicate(root):
            cases = root.findall('testcase')
            cases[1].set('name', cases[0].get('name'))
        self.mutate(duplicate)
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            check_reports(self.directory)

    def test_missing_reports_fail(self):
        for path in self.directory.glob('*.xml'):
            path.unlink()
        with self.assertRaisesRegex(ValueError, 'did not produce'):
            check_reports(self.directory)

    def test_invalid_time_fails(self):
        for value in ('NaN', '-1'):
            self.mutate(lambda root: root.set('time', value))
            with self.assertRaisesRegex(ValueError, 'Invalid JUnit time'):
                check_reports(self.directory)


class MesaGoldenReportsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.host = self.directory / 'host.json'
        self.host_data = {'runId': 'test-run', 'cpu': {'modelName': 'unknown', 'flags': []}}
        self.manifest = self.directory / 'mesa-debs-manifest.txt'
        self.manifest.write_text(
            '# Fixture manifest\n'
            'release-tag: fixture\n'
            + ''.join(
                f'lib{index}_1.0_amd64.deb | lib{index} | 1.0 | amd64 | {"0" * 64} | {category} | {install}\n'
                for index, (category, install) in enumerate(
                    [('B', 'yes'), ('B', 'yes'), ('B', 'yes'), ('A', 'yes'), ('B', 'no')]
                )
            ),
            encoding='utf-8',
        )
        self.host_data['baseLibraries'] = {
            f'lib{index}': {'version': '1.0', 'source': ('runner', 'stored')[index % 2]}
            for index in range(3)
        }
        self.host.write_text(json.dumps(self.host_data), encoding='utf-8')

    def candidate(self, status, indices):
        rgb = b'\x00\x01\x02'
        compressed = gzip.compress(rgb)
        cases = []
        for index in indices:
            case_id = f'c{index:03d}'
            group = 'small' if index <= 58 else 'preview' if index <= 76 else 'export'
            path = self.directory / f'{case_id}.rgb8.gz'
            path.write_bytes(compressed)
            record = dict(id=case_id, group=group, file=path.name, width=1, height=1,
                          rgbSha256=hashlib.sha256(rgb).hexdigest(),
                          fileSha256=hashlib.sha256(compressed).hexdigest(),
                          compressedBytes=len(compressed), bandRowsPassed=[])
            if group == 'export':
                record['bandRowsPassed'] = [64, 256]
                for rows in (64, 256):
                    record[f'bands{rows}'] = dict(changedPixels=0, maxDelta=0, firstChangedXY='')
            cases.append(record)
        return dict(status=status, runId='test-run', caseCount=len(cases), cases=cases)

    def check_candidate(self, capture, observed=frozenset({GOLDEN_CASE})):
        (self.directory / 'capture.json').write_text(json.dumps(capture), encoding='utf-8')
        check_golden(self.directory, observed, manifest_path=self.manifest)

    def test_complete_candidate_passes(self):
        self.check_candidate(self.candidate('complete', range(1, 91)))

    def test_partial_candidate_passes(self):
        self.check_candidate(self.candidate('partial', [1, 76, 90]))

    def test_missing_complete_case_fails(self):
        with self.assertRaisesRegex(ValueError, 'Missing golden IDs.*c090'):
            self.check_candidate(self.candidate('complete', range(1, 90)))

    def test_empty_partial_fails(self):
        with self.assertRaisesRegex(ValueError, 'at least one golden case'):
            self.check_candidate(self.candidate('partial', []))

    def test_unknown_id_fails(self):
        with self.assertRaisesRegex(ValueError, 'Unexpected golden ID.*c091'):
            self.check_candidate(self.candidate('partial', [91]))

    def test_duplicate_id_fails(self):
        with self.assertRaisesRegex(ValueError, 'Duplicate golden ID.*c001'):
            self.check_candidate(self.candidate('partial', [1, 1]))

    def test_file_sha256_mismatch_fails(self):
        capture = self.candidate('partial', [1])
        capture['cases'][0]['fileSha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'fileSha256 mismatch'):
            self.check_candidate(capture)

    def test_rgb_sha256_mismatch_fails(self):
        capture = self.candidate('partial', [1])
        capture['cases'][0]['rgbSha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'rgbSha256 mismatch'):
            self.check_candidate(capture)

    def test_decompressed_size_mismatch_fails(self):
        capture = self.candidate('partial', [1])
        capture['cases'][0]['width'] = 2
        with self.assertRaisesRegex(ValueError, 'decompressed RGB size mismatch'):
            self.check_candidate(capture)

    def test_export_case_with_preview_group_fails(self):
        capture = self.candidate('partial', [77])
        record = capture['cases'][0]
        record.update(group='preview', bandRowsPassed=[])
        del record['bands64'], record['bands256']
        with self.assertRaisesRegex(ValueError, 'c077: group mismatch, expected export'):
            self.check_candidate(capture)

    def test_case_count_mismatch_fails(self):
        capture = self.candidate('partial', [1])
        capture['caseCount'] = 2
        with self.assertRaisesRegex(ValueError, 'caseCount mismatch'):
            self.check_candidate(capture)

    def test_mismatched_run_id_fails(self):
        capture = self.candidate('partial', [1])
        capture['runId'] = 'other-run'
        with self.assertRaisesRegex(ValueError, 'runId must be matching strings'):
            self.check_candidate(capture)

    def test_missing_run_id_fails(self):
        for source in ('capture', 'host'):
            with self.subTest(source=source):
                capture = self.candidate('partial', [1])
                host_data = dict(self.host_data)
                del (capture if source == 'capture' else host_data)['runId']
                self.host.write_text(json.dumps(host_data), encoding='utf-8')
                with self.assertRaisesRegex(ValueError, 'runId must be matching strings'):
                    self.check_candidate(capture)

    def test_missing_band_record_fails(self):
        capture = self.candidate('partial', [90])
        del capture['cases'][0]['bands256']
        with self.assertRaisesRegex(ValueError, 'missing bands256'):
            self.check_candidate(capture)

    def test_missing_host_fails(self):
        self.host.unlink()
        with self.assertRaisesRegex(ValueError, 'Missing golden host.json'):
            self.check_candidate(self.candidate('partial', [1]))

    def test_missing_harness_fails(self):
        with self.assertRaisesRegex(ValueError, 'Missing passing Mesa harness'):
            self.check_candidate(self.candidate('partial', [1]), observed=set())

    def test_missing_host_cpu_fails(self):
        self.host.write_text('{}', encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'requires a cpu object with a modelName string'):
            self.check_candidate(self.candidate('partial', [1]))

    def test_missing_base_libraries_fails(self):
        del self.host_data['baseLibraries']
        self.host.write_text(json.dumps(self.host_data), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'requires a baseLibraries object'):
            self.check_candidate(self.candidate('partial', [1]))

    def test_mismatched_base_libraries_fail(self):
        libraries = self.host_data['baseLibraries']
        libraries['lib3'] = libraries.pop('lib0')
        self.host.write_text(json.dumps(self.host_data), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, r"baseLibraries mismatch: missing=\['lib0'\], extra=\['lib3'\]"):
            self.check_candidate(self.candidate('partial', [1]))

    def test_missing_manifest_fails(self):
        self.manifest = self.directory / 'missing-manifest.txt'
        with self.assertRaisesRegex(ValueError, 'Cannot read Mesa deb manifest'):
            self.check_candidate(self.candidate('partial', [1]))

    def test_manifest_without_targets_fails(self):
        self.manifest.write_text('release-tag: fixture\n', encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'has no category B installation targets'):
            self.check_candidate(self.candidate('partial', [1]))

    def test_default_manifest_candidate_passes(self):
        # リポジトリ内の実マニフェストを参照して候補を作る。
        manifest = Path(__file__).resolve().parent.parent / 'gpu-mesa' / 'mesa-debs-manifest.txt'
        libraries = {}
        for line in manifest.read_text(encoding='utf-8').splitlines():
            line = line.strip()
            if line.startswith('#'):
                continue
            fields = line.split(' | ')
            if len(fields) == 7 and fields[5:] == ['B', 'yes'] and fields[1].strip():
                libraries[fields[1]] = {'version': fields[2], 'source': 'stored'}
        self.host_data['baseLibraries'] = libraries
        self.host.write_text(json.dumps(self.host_data), encoding='utf-8')
        capture = self.candidate('partial', [1])
        (self.directory / 'capture.json').write_text(json.dumps(capture), encoding='utf-8')
        check_golden(self.directory, {GOLDEN_CASE})

    def test_invalid_llvmpipe_value_fails(self):
        self.host_data['llvmpipe'] = {'LP_NATIVE_VECTOR_WIDTH': 128, 'GALLIUM_OVERRIDE_CPU_CAPS': None}
        self.host.write_text(json.dumps(self.host_data), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'llvmpipe must be an object with string or null values'):
            self.check_candidate(self.candidate('partial', [1]))

    def test_invalid_base_library_source_fails(self):
        self.host_data['baseLibraries']['lib0']['source'] = 'unknown'
        self.host.write_text(json.dumps(self.host_data), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'baseLibraries entry lib0 requires a source of runner or stored'):
            self.check_candidate(self.candidate('partial', [1]))

    def test_missing_base_library_version_fails(self):
        del self.host_data['baseLibraries']['lib0']['version']
        self.host.write_text(json.dumps(self.host_data), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'baseLibraries entry lib0 requires a non-empty version string'):
            self.check_candidate(self.candidate('partial', [1]))

    def test_incomplete_candidate_fails(self):
        with self.assertRaisesRegex(ValueError, 'Invalid golden status'):
            self.check_candidate(self.candidate('incomplete', [1]))


if __name__ == '__main__':
    unittest.main()
