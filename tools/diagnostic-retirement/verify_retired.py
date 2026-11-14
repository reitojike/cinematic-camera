"""Guard removal of the four retired app diagnostic variants (no device access)."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

VARIANTS = ("original24Probe", "rawDiagnostic", "gpuDiagnostic", "previewDiagnostic")
ANDROID = "{http://schemas.android.com/apk/res/android}"


def verify_sources(root: Path) -> None:
    gradle = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
    for variant in VARIANTS:
        for suffix in ("", "Test"):
            assert not (root / "app/src" / (variant + suffix)).exists(), variant + suffix
        assert not re.search(r'create\(\s*"' + variant + r'"\s*\)', gradle), variant
        assert "cinematic" + variant[0].upper() + variant[1:] not in gradle, variant
    assert 'cinematicGpuMesa' in gradle, 'Keep the separate Mesa regression path'


def verify_manifest(path: Path) -> None:
    root = ET.parse(path).getroot()
    assert root.get("package") == "dev.cinematic.p0", path
    app = root.find("application")
    assert app is not None, path
    assert app.find("profileable") is None, path
    assert app.get(ANDROID + "largeHeap", "false") == "false", path
    forbidden = ("Diagnostic", "PreviewPresentationActivity", "SyntheticDiagnosticProvider")
    for node in app:
        name = node.get(ANDROID + "name", "")
        assert not any(token in name for token in forbidden), (path, name)


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit("usage: verify_retired.py repository debug-manifest release-manifest")
    verify_sources(Path(sys.argv[1]))
    for arg in sys.argv[2:]:
        verify_manifest(Path(arg))
    print("Four retired variants absent; normal debug/release manifest boundaries preserved.")
