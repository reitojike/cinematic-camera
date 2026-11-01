# Cinematic Camera P0

Pixel 9aでCameraX 1.6.2のRAW保存とCamera2Interopの手動露出を検証する、独立したAndroidプロジェクトです。アプリ名・packageは仮称です。非公開リポジトリは`reitojike/cinematic-camera`です。

## Build

- Android SDK Platform 37、Build Tools 36.0.0
- JDK 17以上（本環境はJDK 25）、Gradle wrapper 9.8.1
- AGP 9.4.1の組み込みKotlin + Compose compiler plugin 2.4.21
- CameraXの直接依存はcore / camera2 / lifecycle / viewの全て1.6.2に固定

SDKをAndroid Studioで選ぶか、`ANDROID_HOME` / gitignore対象の`local.properties`に指定します。

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`。P0の実機手順・採否判断は[docs/P0_REPORT.md](docs/P0_REPORT.md)を参照してください。

## 実機での操作

許可 → Camera ID選択 → JPEG / RAW / RAW+JPEG選択 → AUTOまたはISO/露光時間(ms)を入力してApply MANUAL → Capture。カメラ・出力を変更すると再bindしてAUTOになります。Stop / Startは明示的なunbind / bindです。背景での停止・再開はCameraXとActivityのlifecycleに任せています。

保存先はアプリ内部の`files/captures/`、ログは`files/reports/`です。アンインストール・データ消去で削除されます。ログには時刻、端末ビルド、カメラ能力、設定/結果、ファイル名・サイズ・アプリ内保存先だけを記録し、画像本体・位置情報・端末シリアルは記録しません。バックアップは無効です。ログと撮影物はレビュー後のローカル検証用途に限定し、Gitへ追加しないでください。

権限が恒久拒否された場合はAndroidのアプリ設定で許可してください。操作受付は実測成功ではありません。`ACCEPTED`の後もCaptureResultを比較し、DNGをPCで開くまでは合格扱いにしません。出力の失敗時は部分ファイルが残り得るので、保存成功ログとファイル一覧を照合してください。

LibRaw / NDK、独自現像、動画、GPU / LUT、21:9出力、共有、ギャラリー、バックエンドは実装対象外です。camera-viewの推移依存にcamera-videoが含まれる場合も、動画機能は使用しません。

## CIとAPK配布

think-canvasの運用をP0向けに絞った[運用・配布手順](docs/OPERATIONS.md)を参照してください。main初期化後は作業branch / Draft PRを使います。最小CIはbuild / unit / lint、配布は7日間のActions artifactとmainから作る明示tagのpre-releaseです。P0 APKは一時debug署名で、継続更新用の固定署名は未導入です。非公開repoのため、GitHubへのログインとread accessが必要です。
