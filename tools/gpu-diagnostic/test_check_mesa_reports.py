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
        self.host.write_text(json.dumps({'cpu': {'modelName': 'unknown', 'flags': []}}), encoding='utf-8')

    def candidate(self, status, indices):
        rgb = b'\x00\x01\x02'
        compressed = gzip.compress(rgb)
        cases = []
        for index in indices:
            case_id = f'c{index:03d}'
            group = 'small' if index <= 58 else 'preview' if index <= 76 else 'export'
            path = self.directory / f'{case_id}.rgb8.gz'
            path.write_bytes(compressed)
            record = dict(id=case_id, group=group, file=path.name,
                          rgbSha256=hashlib.sha256(rgb).hexdigest(),
                          fileSha256=hashlib.sha256(compressed).hexdigest(),
                          compressedBytes=len(compressed), bandRowsPassed=[])
            if group == 'export':
                record['bandRowsPassed'] = [64, 256]
                for rows in (64, 256):
                    record[f'bands{rows}'] = dict(changedPixels=0, maxDelta=0, firstChangedXY='')
            cases.append(record)
        return dict(status=status, caseCount=len(cases), cases=cases)

    def check_candidate(self, capture, observed=frozenset({GOLDEN_CASE})):
        (self.directory / 'capture.json').write_text(json.dumps(capture), encoding='utf-8')
        check_golden(self.directory, observed)

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

    def test_incomplete_candidate_fails(self):
        with self.assertRaisesRegex(ValueError, 'Invalid golden status'):
            self.check_candidate(self.candidate('incomplete', [1]))


if __name__ == '__main__':
    unittest.main()
