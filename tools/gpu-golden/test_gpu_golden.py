import copy
import gzip
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zlib
import gpu_golden as g


class GoldenToolsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.candidate = self.root / 'candidate'
        self.candidate.mkdir()
        self.capture = {'schema': 1, 'status': 'complete', 'matrixVersion': 'stage-clear-90-v1',
                        'caseCount': 90, 'fixtureSeed': 20261110, 'baselineApproved': False,
                        'android': dict.fromkeys(('glVendor', 'glRenderer', 'glVersion', 'glslVersion', 'fingerprint'), 'test'),
                        'cases': []}
        for i in range(1, 91):
            name = f'c{i:03}'
            group = 'small' if i <= 58 else 'preview' if i <= 76 else 'export'
            rgb = bytes([i, 0, 255] * 4)
            blob = gzip.compress(rgb, mtime=0)
            (self.candidate / (name + '.rgb8.gz')).write_bytes(blob)
            case = {'id': name, 'group': group, 'kind': 'GREY', 'width': 2, 'height': 2,
                    'strength': .5, 'warmth': 0, 'ev': 0, 'analysis': 'test', 'file': name + '.rgb8.gz',
                    'compressedBytes': len(blob), 'fileSha256': g.digest(blob), 'rgbSha256': g.digest(rgb),
                    'bandRowsPassed': [64, 256] if group == 'export' else []}
            for rows in case['bandRowsPassed']:
                case[f'bands{rows}'] = {'changedPixels': 0, 'maxDelta': 0}
            self.capture['cases'].append(case)
        self.capture['totalImageBytes'] = sum(c['compressedBytes'] for c in self.capture['cases'])
        self.host = {'schema': 1, 'instrumentationPassed': True, 'sourceDirty': False, 'phase': 'complete',
                     'sourceCommit': 'a' * 40, 'sourceTree': 'b' * 40,
                     'appApkSha256': 'c' * 64, 'testApkSha256': 'd' * 64,
                     'environment': dict.fromkeys(('emulatorVersion', 'systemImageRevision', 'avdImage', 'gpuMode', 'gpuFeatures', 'hostGpuDrivers'), 'test')}
        self.save()

    def tearDown(self):
        self.temp.cleanup()

    def save(self):
        (self.candidate / 'capture.json').write_text(json.dumps(self.capture), encoding='utf-8')
        (self.candidate / 'host.json').write_text(json.dumps(self.host), encoding='utf-8')

    def approved(self):
        output = self.root / 'baseline'
        g.approve(self.candidate, output, 'unit-test-only owner approval placeholder')
        return output

    def make_mesa_candidate(self):
        self.capture.pop('android', None)
        self.capture['runId'] = 'full-1'
        self.host = {'schema': 1, 'mesaEnabled': True, 'runId': 'full-1',
                     'sourceCommit': 'a' * 40, 'sourceTree': 'b' * 40,
                     'glVendor': 'Mesa', 'glRenderer': 'llvmpipe (LLVM 21.1.8, 256 bits)',
                     'glVersion': '4.5', 'glslVersion': '4.50', 'osName': 'Linux'}
        self.save()

    def test_valid_mesa_candidate(self):
        self.make_mesa_candidate()
        self.assertEqual(g.validate(self.candidate), (self.capture, self.host))

    def test_mesa_comparison_with_approved_android_baseline(self):
        baseline = self.approved()
        self.make_mesa_candidate()
        output = self.root / 'result'
        self.assertTrue(g.compare(baseline, self.candidate, output))
        result = g.load_json(output / 'comparison.json')
        self.assertEqual(result['status'], 'pass')
        self.assertTrue(result['environmentChanged'])
        self.assertEqual(result['baselineEnvironment']['android']['fingerprint'], 'test')
        self.assertEqual(result['candidateEnvironment']['mesa']['glRenderer'], self.host['glRenderer'])

    def test_invalid_mesa_host_is_rejected(self):
        for field, value, message in (
            ('runId', 'full-2', 'Mesa runId'),
            ('glRenderer', None, 'Missing Mesa identity: glRenderer'),
            ('glRenderer', ' \t', 'Missing Mesa identity: glRenderer'),
            ('glRenderer', 1, 'Missing Mesa identity: glRenderer'),
            ('sourceCommit', 'A' * 40, 'Missing sourceCommit'),
            ('sourceTree', 'B' * 40, 'Missing sourceTree'),
            ('mesaEnabled', 1, 'Android host contains mesaEnabled'),
        ):
            with self.subTest(field=field):
                self.make_mesa_candidate()
                if value is None:
                    del self.host[field]
                else:
                    self.host[field] = value
                self.save()
                with self.assertRaisesRegex(ValueError, message):
                    g.validate(self.candidate)

    def test_android_candidate_cannot_pose_as_mesa(self):
        self.capture['runId'] = 'full-1'
        self.host.update(mesaEnabled=True, runId='full-1')
        self.host.update(dict.fromkeys(g.GL_FIELDS, 'Mesa'))
        self.save()
        with self.assertRaisesRegex(ValueError, 'Mesa host contains Android-only fields'):
            g.validate(self.candidate)

    def test_mesa_candidate_rejects_android_fields(self):
        for field in ('instrumentationPassed', 'sourceDirty', 'phase', 'appApkSha256', 'testApkSha256', 'environment'):
            with self.subTest(field=field):
                self.make_mesa_candidate()
                self.host[field] = None
                self.save()
                with self.assertRaisesRegex(ValueError, 'Mesa host contains Android-only fields'):
                    g.validate(self.candidate)

    def test_mesa_capture_rejects_android_key(self):
        self.make_mesa_candidate()
        self.capture['android'] = None
        self.save()
        with self.assertRaisesRegex(ValueError, 'Mesa capture contains android'):
            g.validate(self.candidate)

    def test_android_candidate_rejects_mesa_enabled_key(self):
        for value in (False, 1, None):
            with self.subTest(value=value):
                self.host['mesaEnabled'] = value
                self.save()
                with self.assertRaisesRegex(ValueError, 'Android host contains mesaEnabled'):
                    g.validate(self.candidate)

    def test_android_host_rejects_mesa_fields(self):
        for field in ('runId', 'glVendor', 'glRenderer', 'glVersion', 'glslVersion',
                      'javaVersion', 'osName', 'osArch', 'cpu', 'llvmpipe', 'baseLibraries'):
            with self.subTest(field=field):
                self.host[field] = None
                self.save()
                with self.assertRaisesRegex(ValueError, f'Android host contains {field}'):
                    g.validate(self.candidate)
                del self.host[field]

    def test_android_capture_rejects_mesa_run_id(self):
        self.capture['runId'] = None
        self.save()
        with self.assertRaisesRegex(ValueError, 'Android capture contains runId'):
            g.validate(self.candidate)

    def test_mesa_comparison_tracks_environment_identity(self):
        for index, (before, after, changed) in enumerate((
            ({'cpu': {'modelName': 'CPU A'}}, {'cpu': {'modelName': 'CPU B'}}, True),
            ({}, {'cpu': {'modelName': 'CPU A'}}, True),
            ({'llvmpipe': {'LP_NATIVE_VECTOR_WIDTH': '128'}},
             {'llvmpipe': {'LP_NATIVE_VECTOR_WIDTH': '256'}}, True),
            ({'llvmpipe': {'GALLIUM_OVERRIDE_CPU_CAPS': 'sse2'}},
             {'llvmpipe': {'GALLIUM_OVERRIDE_CPU_CAPS': 'avx'}}, True),
            ({}, {}, False),
            ({}, {'cpu': {}, 'llvmpipe': {}}, False),
            *(({}, {field: 'changed'}, True) for field in ('glVendor', 'glRenderer', 'glVersion', 'glslVersion')),
        )):
            with self.subTest(before=before, after=after):
                self.make_mesa_candidate()
                mesa_host = self.host.copy()
                self.host.update(before)
                self.save()
                baseline = self.root / f'baseline-{index}'
                g.approve(self.candidate, baseline, 'unit-test-only owner approval placeholder')
                self.host = {**mesa_host, **after}
                self.save()
                output = self.root / f'result-{index}'
                self.assertTrue(g.compare(baseline, self.candidate, output))
                result = g.load_json(output / 'comparison.json')
                self.assertEqual(result['environmentChanged'], changed)

    def test_valid_candidate_and_unchanged_comparison(self):
        g.validate(self.candidate)
        self.assertTrue(g.compare(self.approved(), self.candidate, self.root / 'result'))

    def test_incomplete_and_missing_case_are_rejected(self):
        self.capture['status'] = 'incomplete'
        self.save()
        with self.assertRaises(ValueError): g.validate(self.candidate)
        self.capture['status'] = 'complete'
        self.capture['cases'].pop()
        self.save()
        with self.assertRaises(ValueError): g.validate(self.candidate)

    def test_failed_host_phase_cannot_be_adopted(self):
        self.host['phase'] = 'failed_pull_pending'
        self.save()
        with self.assertRaises(ValueError): g.validate(self.candidate)

    def test_missing_band_is_not_success(self):
        self.capture['cases'][-1]['bandRowsPassed'] = [64]
        self.save()
        with self.assertRaises(ValueError): g.validate(self.candidate)

    def test_corruption_and_path_escape_are_rejected(self):
        (self.candidate / 'c001.rgb8.gz').write_bytes(b'invalid')
        with self.assertRaises(ValueError): g.validate(self.candidate)
        self.capture['cases'][0]['file'] = '../elsewhere.rgb8.gz'
        self.save()
        with self.assertRaises(ValueError): g.validate(self.candidate)

    def test_budget_and_unrecorded_image_rejected(self):
        self.capture['totalImageBytes'] = g.LIMIT + 1
        self.save()
        with self.assertRaises(ValueError): g.validate(self.candidate)
        self.capture['totalImageBytes'] = sum(c['compressedBytes'] for c in self.capture['cases'])
        self.save()
        (self.candidate / 'extra.rgb8.gz').write_bytes(b'x')
        with self.assertRaises(ValueError): g.validate(self.candidate)

    def test_no_approval_or_tampered_approval_rejected(self):
        with self.assertRaises(FileNotFoundError): g.compare(self.candidate, self.candidate, self.root / 'result')
        baseline = self.approved()
        host = copy.deepcopy(self.host)
        host['sourceCommit'] = 'e' * 40
        (baseline / 'host.json').write_text(json.dumps(host), encoding='utf-8')
        with self.assertRaises(ValueError): g.compare(baseline, self.candidate, self.root / 'result')

    def test_changed_environment_passes_without_pixel_diff(self):
        baseline = self.approved()
        self.host['environment']['emulatorVersion'] = 'new-version'
        self.capture['android']['fingerprint'] = 'aosp-build'
        self.save()
        self.assertTrue(g.compare(baseline, self.candidate, self.root / 'result'))
        result = g.load_json(self.root / 'result' / 'comparison.json')
        self.assertTrue(result['environmentChanged'])
        self.assertEqual(result['status'], 'pass')
        self.assertEqual(result['candidateEnvironment']['host']['emulatorVersion'], 'new-version')
        self.assertEqual(result['baselineEnvironment']['android']['fingerprint'], 'test')
        self.assertEqual(result['candidateEnvironment']['android']['fingerprint'], 'aosp-build')

    def test_changed_pixels_emit_location_and_magnitude(self):
        self.check_changed_pixels(False)

    def test_changed_environment_with_pixel_difference_still_holds(self):
        self.check_changed_pixels(True)

    def check_changed_pixels(self, change_environment):
        baseline = self.approved()
        if change_environment:
            self.host['environment']['hostGpuDrivers'] = 'different-driver'
        case = self.capture['cases'][0]
        rgb = bytearray(g.pixels(self.candidate, case)); rgb[3] += 2
        blob = gzip.compress(rgb, mtime=0)
        (self.candidate / case['file']).write_bytes(blob)
        case.update(rgbSha256=g.digest(rgb), fileSha256=g.digest(blob), compressedBytes=len(blob))
        self.capture['totalImageBytes'] = sum(c['compressedBytes'] for c in self.capture['cases'])
        self.save()
        self.assertFalse(g.compare(baseline, self.candidate, self.root / 'result'))
        result = g.load_json(self.root / 'result' / 'comparison.json')['cases'][0]
        self.assertEqual((result['changedPixels'], result['maxDelta'], result['boundingBox']), (1, 2, [1, 0, 1, 0]))
        self.assertTrue((self.root / 'result' / 'c001-diff32.png').exists())

    def test_changed_environment_does_not_bypass_failed_instrumentation_or_bands(self):
        baseline = self.approved()
        self.host['environment']['emulatorVersion'] = 'new-version'
        self.host['instrumentationPassed'] = False
        self.save()
        with self.assertRaises(ValueError): g.compare(baseline, self.candidate, self.root / 'failed')
        self.host['instrumentationPassed'] = True
        self.capture['cases'][-1]['bands64']['changedPixels'] = 1
        self.save()
        with self.assertRaises(ValueError): g.compare(baseline, self.candidate, self.root / 'bands')

    def test_missing_environment_metadata_is_rejected(self):
        baseline = self.approved()
        del self.host['environment']['hostGpuDrivers']
        self.save()
        with self.assertRaises(ValueError): g.compare(baseline, self.candidate, self.root / 'missing')

    def test_all_rgb_percentile_and_no_change(self):
        a = bytes(3000); b = bytearray(a); b[0] = 1
        result = g.summary(a, b, 1000, 1)
        self.assertEqual((result['maxDelta'], result['p999AllRgb'], result['changedPixels']), (1, 0, 1))
        self.assertEqual(g.summary(a, a, 1000, 1)['boundingBox'], None)

    def test_png_round_trip_bytes_and_no_overwrite(self):
        rgb = bytes(range(12)); target = self.root / 'test.png'
        g.png(target, 2, 2, rgb)
        data = target.read_bytes(); self.assertEqual(data[:8], b'\x89PNG\r\n\x1a\n')
        position = 8; compressed = b''
        while position < len(data):
            size = struct.unpack('>I', data[position:position + 4])[0]
            kind = data[position + 4:position + 8]
            payload = data[position + 8:position + 8 + size]
            self.assertEqual(struct.unpack('>I', data[position + 8 + size:position + 12 + size])[0], zlib.crc32(kind + payload))
            if kind == b'IDAT': compressed += payload
            position += size + 12
        self.assertEqual(zlib.decompress(compressed), b'\0' + rgb[:6] + b'\0' + rgb[6:])
        with self.assertRaises(FileExistsError): g.review(self.candidate, self.candidate)


if __name__ == '__main__':
    unittest.main()
