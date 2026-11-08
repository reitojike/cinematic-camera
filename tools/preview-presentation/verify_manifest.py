"""専用UI診断hostの隔離だけを検査する。提示性能は判定しない。"""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

A = "{http://schemas.android.com/apk/res/android}"

def require(condition):
    if not condition:
        raise ValueError("UI診断manifestの隔離条件が一致しません")

def verify(path):
    root = ET.parse(path).getroot()
    app = root.find("application")
    require(root.get("package") == "dev.cinematic.p0.previewdiagnostic")
    require(app is not None)
    require(app.get(A + "debuggable", "false") == "false")
    require(app.get(A + "largeHeap", "false") == "false")
    require(app.get(A + "allowBackup") == "false")
    require(app.find("profileable").get(A + "shell") == "true")
    require(not any(root.findall(tag) for tag in ("uses-permission", "uses-permission-sdk-23", "permission", "uses-feature")))
    require(not any(app.findall(tag) for tag in ("service", "provider", "receiver", "activity-alias")))
    activities = app.findall("activity")
    require(len(activities) == 1)
    require(activities[0].get(A + "name") == "dev.cinematic.p0.photoroll.PreviewPresentationActivity")
    require(activities[0].get(A + "exported") == "false")
    require(not activities[0].findall("intent-filter"))

if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("マージ済みmanifestを1件指定してください")
    verify(Path(sys.argv[1]))
    print("専用UI診断hostの隔離を確認しました。実表示は未計測です。")
