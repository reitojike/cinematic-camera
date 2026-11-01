# Cinematic Camera P0

Pixel 9aでCameraX 1.6.2のRAW保存とCamera2Interopの手動露出を検証する、独立したAndroidプロジェクトです。
アプリ名・packageは仮称です。リポジトリは`reitojike/cinematic-camera`（公開前検査後に承認を得てpublic化）。

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

共有保存候補版の新しいJPEG/DNGは、標準MediaStoreの`Pictures/Cinematic Camera/`へ保存します。
撮影ボタンの下のState / Recent logでURI・名前・byte数を確認できます。JPEGのGoogleフォト等での表示と
DNGの閲覧対応は実機未確認です。画像本体は再エンコードせずコピーし、広範囲ストレージ権限を追加しません。
API29未満では共有保存は非対応として表示します。ギャラリーやSNSのアプリ内UI、旧写真の移行は作りません。

**現在のpre-release `v0.0.1-dev.1`は旧内部保存版**です。旧写真は`files/captures/`にあり、
アプリ削除で失われます。ログは新旧ともprivateの`files/reports/`。ログに画像本体・位置情報・端末シリアルは
記録せず、写真や完全な実機ログはGitへ追加しないでください。共有保存候補の配布状態は
[REPOSITORY_STATUS](docs/REPOSITORY_STATUS.md)を参照してください。

権限が恒久拒否された場合はAndroidのアプリ設定で許可してください。操作受付は実測成功ではありません。`ACCEPTED`の後もCaptureResultを比較し、DNGをPCで開くまでは合格扱いにしません。出力の失敗時は部分ファイルが残り得るので、保存成功ログとファイル一覧を照合してください。

LibRaw / NDK、独自現像、動画、GPU / LUT、21:9出力、共有、ギャラリー、バックエンドは実装対象外です。camera-viewの推移依存にcamera-videoが含まれる場合も、動画機能は使用しません。

## CIとAPK配布

think-canvasの運用をP0向けに絞った[運用・配布手順](docs/OPERATIONS.md)を参照してください。main初期化後は作業branch / Draft PRを使います。最小CIはbuild / unit / lint、配布は7日間のActions artifactとmainから作る明示tagのpre-releaseです。P0 APKは一時debug署名で、継続更新用の固定署名は未導入です。

## 仕様とSpec Kit

[GitHub Spec Kit 1.2.5](docs/SPEC_KIT.md)をCodex skillsで利用します。[P0 spec](specs/001-p0-camera-probe/spec.md)、
[plan](specs/001-p0-camera-probe/plan.md)、[tasks](specs/001-p0-camera-probe/tasks.md)で合意内容・設計・残件を追跡します。
実装済みのチェックとPixel 9a実機ACの合格は別です。インストールとカメラ操作にはユーザー報告がありますが、
実機ACは未完了、P1未判定です。
