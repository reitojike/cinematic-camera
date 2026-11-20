import tempfile
import unittest
import struct
import zlib
from pathlib import Path
from PIL import Image
from verify import verify_tests, verify_png, verify_and_stage_canvas


class VerificationTest(unittest.TestCase):
    def check_xml(self, xml):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / 'TEST-fixture.xml'
            p.write_text(xml)
            return verify_tests([p], {'Example': ['works']})

    def test_success(self):
        self.assertEqual(1, self.check_xml('<testsuite><testcase classname="Example" name="works"/></testsuite>'))

    def test_missing_extra_duplicate_skip_and_error_fail(self):
        for body in ['', '<testcase classname="Example" name="other"/>',
                     '<testcase classname="Example" name="works"/>' * 2,
                     '<testcase classname="Example" name="works"><skipped/></testcase>',
                     '<testcase classname="Example" name="works"><error/></testcase>',
                     '<testcase classname="Example" name="works"><failure/></testcase>']:
            with self.subTest(body=body), self.assertRaises(ValueError):
                self.check_xml('<testsuite>' + body + '</testsuite>')

    def test_only_allowlisted_diagnostics_are_reported(self):
        xml = ('<testsuite failures="1"><testcase classname="Example" name="works">'
               '<failure type="java.lang.IllegalStateException" message="PRIVATE_TEXT">'
               'PRIVATE_URI at Example.works(Example.kt:47)</failure>'
               '</testcase></testsuite>')
        with self.assertRaises(ValueError) as ctx:
            self.check_xml(xml)
        self.assertEqual('ATD_METHOD_FAILURE Example#works: failure type=java.lang.IllegalStateException test_line=47',
                         str(ctx.exception))
        self.assertNotIn('PRIVATE', str(ctx.exception))

    def test_unexpected_method_fields_are_not_reported(self):
        xml = ('<testsuite failures="1"><testcase classname="PRIVATE_CLASS" name="PRIVATE_METHOD">'
               '<failure type="PRIVATE_TYPE">PRIVATE_FRAME</failure></testcase></testsuite>')
        with self.assertRaises(ValueError) as ctx:
            self.check_xml(xml)
        self.assertEqual('ATD_METHOD_FAILURE unexpected method: failure', str(ctx.exception))

    def test_suite_error_fails(self):
        with self.assertRaises(ValueError):
            self.check_xml('<testsuite errors="1"><testcase classname="Example" name="works"/></testsuite>')

    def test_no_files_fails(self):
        with self.assertRaises(ValueError):
            verify_tests([], {'Example': ['works']})

    def test_bad_png_fails(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / 'a.png'
            p.write_bytes(b'not a PNG')
            with self.assertRaises(ValueError):
                verify_png(p)

    def test_png_decoder_rejects_invalid_filter(self):
        def chunk(kind, data):
            return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / 'a.png'
            header = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 1, 1, 8, 6, 0, 0, 0))
            for filt in (0, 255):
                p.write_bytes(header + chunk(b'IDAT', zlib.compress(bytes([filt, 0, 0, 0, 255]))) + chunk(b'IEND', b''))
                if filt == 0:
                    self.assertEqual((1, 1), verify_png(p))
                else:
                    with self.assertRaises((ValueError, OSError)):
                        verify_png(p)

    def test_only_approved_canvas_is_staged_under_the_reviewed_name(self):
        with tempfile.TemporaryDirectory() as d:
            base = Path(d)
            root, stage = base / 'additional', base / 'approved'
            approved = root / 'runner-device' / 'canvas' / 'expected.png'
            approved.parent.mkdir(parents=True)
            Image.new('RGB', (320, 320)).save(approved)
            Image.new('RGB', (320, 320)).save(approved.with_name('unexpected.png'))
            verify_and_stage_canvas(root, ['canvas/expected.png'], stage)
            self.assertEqual(['canvas/expected.png'], [p.relative_to(stage).as_posix()
                                                       for p in stage.rglob('*.png')])
            self.assertEqual(approved.read_bytes(), (stage / 'canvas/expected.png').read_bytes())

    def test_expected_canvas_outside_input_root_does_not_allow_partial_publication(self):
        with tempfile.TemporaryDirectory() as d:
            base = Path(d)
            root, stage = base / 'additional', base / 'approved'
            root.mkdir()
            Image.new('RGB', (320, 320)).save(root / 'present.png')
            Image.new('RGB', (320, 320)).save(base / 'outside.png')
            with self.assertRaises(ValueError):
                verify_and_stage_canvas(root, ['present.png', 'outside.png'], stage)
            self.assertFalse(stage.exists())
