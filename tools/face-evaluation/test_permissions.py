import unittest
from audit import validate_permissions, NETWORK_PERMISSIONS


class PermissionTest(unittest.TestCase):
    def test_mlkit_diagnostics_only(self):
        validate_permissions("mlkit", list(NETWORK_PERMISSIONS))
        validate_permissions("mlkit", list(NETWORK_PERMISSIONS) +
                             ["org.cinematic.evaluation.mlkit.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"])

    def test_baseline_and_yunet_have_no_network(self):
        for flavor in ("baseline", "yunet"):
            validate_permissions(flavor, [])
            with self.assertRaises(ValueError):
                validate_permissions(flavor, list(NETWORK_PERMISSIONS))

    def test_missing_network_is_not_normal_mlkit_configuration(self):
        with self.assertRaises(ValueError):
            validate_permissions("mlkit", ["android.permission.INTERNET"])

    def test_unexpected_data_access_rejected(self):
        for permission in ("android.permission.CAMERA", "android.permission.READ_MEDIA_IMAGES",
                           "android.permission.READ_CONTACTS", "other.app.permission.ACCESS"):
            with self.subTest(permission=permission), self.assertRaises(ValueError):
                validate_permissions("mlkit", list(NETWORK_PERMISSIONS) + [permission])

    def test_play_diagnostics_only_and_no_photo_access(self):
        validate_permissions("mlkitplay", list(NETWORK_PERMISSIONS))
        for permission in ("android.permission.CAMERA", "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_EXTERNAL_STORAGE"):
            with self.assertRaises(ValueError):
                validate_permissions("mlkitplay", list(NETWORK_PERMISSIONS) + [permission])

    def test_play_model_availability_guard_precedes_inference(self):
        from pathlib import Path
        source = (Path(__file__).parent / "benchmark/src/mlkitplay/java/org/cinematic/evaluation/DetectorFactory.java").read_text(encoding="utf-8-sig")
        self.assertLess(source.index("areModulesAvailable(sdk)"), source.index("sdk.process(image)"))
        self.assertIn("throw new Detector.NotReadyException()", source)
        self.assertNotIn("installModules(", source)
        self.assertNotIn("deferredInstall(", source)

    def test_comparison_diagnostics_only_and_no_photo_permission(self):
        validate_permissions("comparison", list(NETWORK_PERMISSIONS))
        with self.assertRaises(ValueError):
            validate_permissions("comparison", list(NETWORK_PERMISSIONS) + ["android.permission.READ_MEDIA_IMAGES"])

    def test_comparison_not_in_product_and_no_play_delivery(self):
        from pathlib import Path
        root = Path(__file__).parent
        build = (root / "benchmark/build.gradle.kts").read_text(encoding="utf-8")
        comparison_dependencies = [line for line in build.splitlines() if '"comparisonImplementation"' in line]
        self.assertFalse(any('play-services-mlkit' in line for line in comparison_dependencies))
        activity = (root / "benchmark/src/comparison/java/org/cinematic/evaluation/ComparisonActivity.java").read_text(encoding="utf-8")
        self.assertIn('Intent.EXTRA_LOCAL_ONLY, true', activity)
        self.assertIn('FLAG_SECURE', activity)
        self.assertNotIn('ACTION_SEND', activity)
        self.assertNotIn('takePersistableUriPermission', activity)
        main_settings = (root.parent.parent / "settings.gradle.kts").read_text(encoding="utf-8")
        self.assertNotIn('face-evaluation', main_settings)
