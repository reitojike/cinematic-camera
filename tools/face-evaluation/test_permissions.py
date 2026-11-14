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
