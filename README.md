# Cinematic Camera

ライブパフォーマンスの静止画/動画を最優先に、風景・ポートレートでも映画的な色と構図を楽しむAndroidアプリを目指します。
現在の実装はPixel 9aでCameraX 1.6.2のRAW保存とCamera2Interopの手動露出を検証するP0です。
アプリ名・packageは仮称です。リポジトリは`reitojike/cinematic-camera`（公開前検査後に承認を得てpublic化）。

[製品方針](specs/002-live-capture/spec.md)と[検証・開発計画](specs/002-live-capture/plan.md)を参照してください。
P0 → P0.5ライブ技術検証 → P1 Stage Clear → P2写真/動画ルック統合 → P3非破壊crop/出力/共有/製品UIの順です。
Stage Dramatic / Cinema T&O、custom編集やLUT入出力は後続です。動画・ルック・編集UIはまだ実装していません。

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

共有保存版の新しいJPEG/DNGは、標準MediaStoreの`Pictures/Cinematic Camera/`へ保存します。
撮影ボタンの下のState / Recent logでURI・名前・byte数を確認できます。JPEGのGoogleフォト等での表示と
DNGの閲覧対応は[最終検証結果](docs/P0_REPORT.md)のユーザー報告と未取得資料を区別してください。
画像本体は再エンコードせずコピーし、広範囲ストレージ権限を追加しません。
API29未満では共有保存は非対応として表示します。ギャラリーやSNSのアプリ内UI、旧写真の移行は作りません。

**共有保存のpre-releaseは[v0.0.2-dev.1](https://github.com/reitojike/cinematic-camera/releases/tag/v0.0.2-dev.1)**です。
旧`v0.0.1-dev.1`の内部写真は`files/captures/`にあり、
アプリ削除で失われます。ログは新旧ともprivateの`files/reports/`。ログに画像本体・位置情報・端末シリアルは
記録せず、写真や完全な実機ログはGitへ追加しないでください。署名差と手動更新の注意はReleaseページを参照してください。

権限が恒久拒否された場合はAndroidのアプリ設定で許可してください。操作受付は実測成功ではありません。`ACCEPTED`の後もCaptureResultを比較し、DNGをPCで開くまでは合格扱いにしません。出力の失敗時は部分ファイルが残り得るので、保存成功ログとファイル一覧を照合してください。

現行P0ではLibRaw / NDK、独自現像、動画、GPU / LUT、21:9出力、共有、ギャラリー、バックエンドは未実装です。
camera-viewの推移依存にcamera-videoが含まれる場合も、現行P0は動画機能を使用していません。

## CIとAPK配布

think-canvasの運用をP0向けに絞った[運用・配布手順](docs/OPERATIONS.md)を参照してください。main初期化後は作業branch / Draft PRを使います。最小CIはbuild / unit / lint、配布は7日間のActions artifactとmainから作る明示tagのpre-releaseです。P0 APKは一時debug署名で、継続更新用の固定署名は未導入です。

## 仕様とSpec Kit

[GitHub Spec Kit 1.2.5](docs/SPEC_KIT.md)をCodex skillsで利用します。[P0 spec](specs/001-p0-camera-probe/spec.md)、
[plan](specs/001-p0-camera-probe/plan.md)、[tasks](specs/001-p0-camera-probe/tasks.md)で合意内容・設計・残件を追跡します。
実装済みのチェックとPixel 9a実機ACの合格は別です。インストールとカメラ操作にはユーザー報告がありますが、
実機の詳細証拠と追加条件の確認は[Issue #4](https://github.com/reitojike/cinematic-camera/issues/4)で管理します。
P1判断案は制約付きGoであり、新フェーズ実装の承認とは別です。
