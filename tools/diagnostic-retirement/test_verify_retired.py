from pathlib import Path
import tempfile
import unittest
from verify_retired import verify_manifest, verify_sources


class RetirementContractTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / 'app/src').mkdir(parents=True)
        (self.root / 'app/build.gradle.kts').write_text('val gpuMesa = "cinematicGpuMesa"')

    def manifest(self, contents):
        path = self.root / 'manifest.xml'
        path.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.cinematic.p0"><application>' + contents + '</application></manifest>')
        return path

    def test_normal_sources_and_manifest_pass(self):
        verify_sources(self.root)
        verify_manifest(self.manifest('<activity android:name="dev.cinematic.p0.photoroll.PhotoRollActivity"/>'))

    def test_each_retired_source_set_is_rejected(self):
        for variant in ('original24Probe', 'rawDiagnostic', 'gpuDiagnostic', 'previewDiagnostic'):
            path = self.root / 'app/src' / variant
            path.mkdir()
            with self.assertRaises(AssertionError):
                verify_sources(self.root)
            path.rmdir()

    def test_reintroduced_gradle_variant_is_rejected(self):
        (self.root / 'app/build.gradle.kts').write_text('cinematicGpuMesa\ncreate("gpuDiagnostic")')
        with self.assertRaises(AssertionError):
            verify_sources(self.root)

    def test_diagnostic_and_profileable_entries_are_rejected(self):
        for child in ('<profileable android:shell="true"/>', '<activity android:name="dev.cinematic.p0.rawdiagnostic.RawDiagnosticActivity"/>', '<activity android:name="dev.cinematic.p0.photoroll.PreviewPresentationActivity"/>'):
            with self.assertRaises(AssertionError):
                verify_manifest(self.manifest(child))

    def test_wrong_package_is_rejected(self):
        path = self.manifest('')
        path.write_text(path.read_text().replace('dev.cinematic.p0', 'dev.cinematic.p0.gpudiagnostic'))
        with self.assertRaises(AssertionError):
            verify_manifest(path)
