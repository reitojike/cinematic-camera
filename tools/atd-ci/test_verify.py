import tempfile
import unittest
import struct
import zlib
from pathlib import Path
from verify import verify_tests, verify_png


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
