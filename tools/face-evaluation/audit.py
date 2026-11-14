"""Audit local evaluation APKs/manifests; emits no local paths or per-image data."""
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile

ANDROID = "{http://schemas.android.com/apk/res/android}"


NETWORK_PERMISSIONS = {"android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"}


def validate_permissions(flavor, permissions):
    """Allow SDK diagnostics only in ML Kit; reject all other platform permissions."""
    platform = {p for p in permissions if p.startswith("android.permission.")}
    expected = NETWORK_PERMISSIONS if flavor == "mlkit" else set()
    if platform != expected:
        raise ValueError("Unexpected platform permissions for evaluation flavor")
    # AndroidX may add only this package's signature-protected receiver permission.
    own = f"org.cinematic.evaluation.{flavor}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    if set(permissions) - platform - {own}:
        raise ValueError("Unexpected custom permission")


def audit(root):
    result = {}
    for flavor in ("baseline", "mlkit", "yunet"):
        apk = root / f"outputs/apk/{flavor}/debug/benchmark-{flavor}-debug.apk"
        manifest = root / f"intermediates/merged_manifests/{flavor}Debug/process{flavor.capitalize()}DebugManifest/AndroidManifest.xml"
        xml = ET.parse(manifest).getroot()
        permissions = [node.get(ANDROID + "name") for node in xml if node.tag.startswith("uses-permission")]
        validate_permissions(flavor, permissions)
        with zipfile.ZipFile(apk) as archive:
            entries = archive.infolist()
            notices = [x.filename for x in entries if any(s in x.filename.lower() for s in ("license", "notice", "third_party"))]
            native = sum(x.file_size for x in entries if x.filename.startswith("lib/"))
            abis = sorted({x.filename.split('/')[1] for x in entries if x.filename.startswith("lib/")})
            if not set(abis).issubset({"arm64-v8a", "x86_64"}):
                raise ValueError("Unexpected ABI")
            model = [x for x in entries if x.filename.endswith("face_detection_yunet_2023mar.onnx")]
            if flavor == "yunet":
                if len(model) != 1 or hashlib.sha256(archive.read(model[0])).hexdigest() != "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4":
                    raise ValueError("Packaged model mismatch")
            elif model:
                raise ValueError("Model leaked across flavors")
        app = xml.find("application")
        result[flavor] = {"apk_bytes": apk.stat().st_size, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
                          "native_uncompressed_bytes": native, "abis": abis, "permissions": permissions, "notice_entries": notices,
                          "components": [{"kind": n.tag, "name": n.get(ANDROID + "name"), "exported": n.get(ANDROID + "exported")}
                                         for n in app if n.tag in ("provider", "service", "receiver", "activity")]}
    for row in result.values():
        row["delta_from_baseline_bytes"] = row["apk_bytes"] - result["baseline"]["apk_bytes"]
    return result


if __name__ == "__main__":
    print(json.dumps(audit(Path(__file__).parent / "benchmark/build"), indent=2))
