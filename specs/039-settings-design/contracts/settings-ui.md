# Settings UI Contract

- 設定開閉は同じPreviewViewに重なるoverlay。空白shieldを維持。
- 形式/解像度/カメラ/動画/音声/照明/ロックは従来のCameraProbe入口へ委譲する。UIが新しい制御キューを作らない。
- 明色選択は要求を示し、再接続/未対応は枠と文言を併用。対応カメラをID0/1で固定しない。
- 本文と管理footerを分けても、診断のenabled条件（復旧例外を含む）とresetのbound条件を保持する。
- リセット確認の取消/Backは無変更。全リセットは実行時guardを再確認する。
- スクリーンリーダーに伝えるラベルと状態の意味を保持し、ON/OFF/無効理由を外観だけにしない。
- 原本、RAW処理、保存照合、ticket、Bitmap、復帰cacheはこのUIの所有外。
