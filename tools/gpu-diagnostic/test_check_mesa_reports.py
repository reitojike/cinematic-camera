import tempfile
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET
from check_mesa_reports import REQUIRED, check_reports


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


if __name__ == '__main__':
    unittest.main()
