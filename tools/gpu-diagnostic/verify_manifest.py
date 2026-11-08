"""GPU診断APKと通常APKの起動・権限境界を検証する。GPU自体は起動しない。"""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

A = "{http://schemas.android.com/apk/res/android}"


def verify(path: Path, diagnostic: bool) -> None:
    root = ET.parse(path).getroot()
    app = root.find("application")
    assert app is not None
    if not diagnostic:
        assert app.find("profileable") is None, path
        for node in app:
            assert "GpuDiagnostic" not in node.get(A + "name", ""), path
        return
    assert root.get("package") == "dev.cinematic.p0.gpudiagnostic"
    assert app.get(A + "debuggable", "false") == "false"
    assert app.get(A + "largeHeap", "false") == "false"
    assert app.get(A + "allowBackup") == "false"
    assert app.find("profileable").get(A + "shell") == "true"
    assert {p.get(A + "name") for p in root.findall("uses-permission")} == {
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
        "android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING",
        "android.permission.POST_NOTIFICATIONS",
    }
    assert not root.findall("uses-feature")
    assert not any(app.findall(tag) for tag in ("provider", "receiver", "activity-alias"))
    activities = app.findall("activity")
    assert len(activities) == 1
    assert activities[0].get(A + "name") == "dev.cinematic.p0.raw.GpuDiagnosticActivity"
    services = app.findall("service")
    assert len(services) == 1
    assert services[0].get(A + "name") == "dev.cinematic.p0.raw.GpuDiagnosticService"
    assert services[0].get(A + "exported") == "false"
    assert set(services[0].get(A + "foregroundServiceType").split("|")) == {"dataSync", "mediaProcessing"}


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit("GPU manifest と任意の通常manifestを指定してください")
    verify(Path(sys.argv[1]), True)
    for arg in sys.argv[2:]:
        verify(Path(arg), False)
    print("GPU診断のmanifest境界を確認しました。GPU実行はしていません。")