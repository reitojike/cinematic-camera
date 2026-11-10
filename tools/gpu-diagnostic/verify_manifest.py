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
            assert not any(name in node.get(A + "name", "") for name in ("GpuDiagnostic", "DiagnosticHub", "PreviewParallelDiagnostic", "GpuPreviewDiagnostic")), path
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
    assert {n.get(A + "name") for n in activities} == {
        "dev.cinematic.p0.raw.DiagnosticHubActivity",
        "dev.cinematic.p0.gpu.GpuPreviewDiagnosticActivity",
        "dev.cinematic.p0.raw.GpuDiagnosticActivity",
        "dev.cinematic.p0.raw.PreviewParallelDiagnosticActivity",
        "dev.cinematic.p0.rawdiagnostic.RawDiagnosticActivity",
        "dev.cinematic.p0.photoroll.PhotoRollActivity",
    }
    launchers = {
        "dev.cinematic.p0.raw.DiagnosticHubActivity",
        "dev.cinematic.p0.gpu.GpuPreviewDiagnosticActivity",
    }
    for activity in activities:
        is_launcher = activity.get(A + "name") in launchers
        assert activity.get(A + "exported") == ("true" if is_launcher else "false")
        filters = activity.findall("intent-filter")
        assert len(filters) == (1 if is_launcher else 0)
        if is_launcher:
            assert {n.get(A + "name") for n in filters[0].findall("action")} == {"android.intent.action.MAIN"}
            assert {n.get(A + "name") for n in filters[0].findall("category")} == {"android.intent.category.LAUNCHER"}
            assert not filters[0].findall("data")
        if activity.get(A + "name") == "dev.cinematic.p0.gpu.GpuPreviewDiagnosticActivity":
            assert activity.get(A + "label") == "GPU 表示診断"
    services = app.findall("service")
    assert {n.get(A + "name") for n in services} == {
        "dev.cinematic.p0.raw.GpuDiagnosticService",
        "dev.cinematic.p0.photoroll.ForegroundExportService",
    }
    for service in services:
        assert service.get(A + "exported") == "false"
        assert set(service.get(A + "foregroundServiceType").split("|")) == {"dataSync", "mediaProcessing"}


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit("GPU manifest と任意の通常manifestを指定してください")
    verify(Path(sys.argv[1]), True)
    for arg in sys.argv[2:]:
        verify(Path(arg), False)
    print("GPU診断のmanifest境界を確認しました。GPU実行はしていません。")