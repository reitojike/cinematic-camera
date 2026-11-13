"""Validate generated variant identities/flags and unchanged permission declarations."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

build = Path(sys.argv[1])
android = "{http://schemas.android.com/apk/res/android}"
permissions = []
for variant in ("debug", "release", "memoryDiagnostic"):
    expected = variant == "memoryDiagnostic"
    config = build / "generated/source/buildConfig" / variant / "dev/cinematic/p0/BuildConfig.java"
    text = config.read_text(encoding="utf-8")
    assert f'BUILD_TYPE = "{variant}";' in text, variant
    assert f'MEMORY_ADMISSION_RECORD_ONLY = {str(expected).lower()};' in text, variant
    identity = "dev.cinematic.p0.memorydiagnostic" if expected else "dev.cinematic.p0"
    assert f'APPLICATION_ID = "{identity}";' in text, variant
    cap = variant[0].upper() + variant[1:]
    manifest = build / f"intermediates/merged_manifest/{variant}/process{cap}MainManifest/AndroidManifest.xml"
    root = ET.parse(manifest).getroot()
    assert root.get("package") == identity, variant
    # AndroidX scopes its non-exported receiver signature permission to applicationId.
    receiver = identity + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    declarations = root.findall("permission")
    assert len(declarations) == 1
    assert declarations[0].get(android + "name") == receiver
    assert declarations[0].get(android + "protectionLevel") == "signature"
    normalized = []
    for item in root.findall("uses-permission"):
        attrs = dict(item.attrib)
        if attrs.get(android + "name") == receiver:
            attrs[android + "name"] = "APPLICATION_ID.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        normalized.append(tuple(sorted(attrs.items())))
    permissions.append(sorted(normalized))
    app = root.find("application")
    if expected:
        assert app.get(android + "name") == "dev.cinematic.p0.memory.MemoryDiagnosticApplication"
        assert app.get(android + "label") == "Cinematic MEMORY DIAGNOSTIC"
        assert app.get(android + "debuggable") == "true"
    else:
        assert app.get(android + "name") != "dev.cinematic.p0.memory.MemoryDiagnosticApplication"
assert permissions[0] == permissions[1] == permissions[2], "diagnostic changed permissions"
test_manifest = build / "intermediates/merged_manifest/memoryDiagnosticAndroidTest/mergeMemoryDiagnosticAndroidTestManifest/AndroidManifest.xml"
test_root = ET.parse(test_manifest).getroot()
runners = test_root.findall("instrumentation")
assert len(runners) == 1
assert runners[0].get(android + "name") == "androidx.test.runner.AndroidJUnitRunner"
assert runners[0].get(android + "targetPackage") == "dev.cinematic.p0.memorydiagnostic"
print("MEMORY_DIAGNOSTIC_ISOLATION PASS: generated flag/identity/label, unchanged permissions")
