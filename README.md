# Cinematic Camera

ライブパフォーマンスの静止画/動画を最優先に、風景・ポートレートでも映画的な色と構図を楽しむAndroidアプリを目指します。
CameraX の撮影基盤に加え、カメラロール、手動DNG現像・編集・JPEG保存を実装しています。現在の契約と未採用の範囲は [Living Spec](specs/README.md) を参照してください。

## Build

- Android SDK Platform 37、Build Tools 36.0.0
- JDK 17以上（本環境はJDK 25）、Gradle wrapper 9.8.1
- AGP 9.4.1の組み込みKotlin + Compose compiler plugin 2.4.21
- CameraXの直接依存はcore / camera2 / lifecycle / view / videoの全て1.6.2に固定

SDKをAndroid Studioで選ぶか、`ANDROID_HOME` / gitignore対象の`local.properties`に指定します。

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`。

## CIとAPK配布

検証と配布の手順は [運用・配布手順](docs/OPERATIONS.md) を参照してください。
配布版は [GitHub Releases](https://github.com/reitojike/cinematic-camera/releases) で、対象タグ・ソース・APKの署名とハッシュを確認してください。
インストールや新しい版の公開は個別承認が必要です。

## 仕様とSpec Kit

現行仕様は [Living Spec](specs/README.md)、横断制約は [Constitution](.specify/memory/constitution.md) を参照してください。
変更計画・採否・検証結果は関連する Issue / PR で追跡します。
[Spec Kit の運用](docs/SPEC_KIT.md) に従い、作業ブランチと Draft PR を使います。
マージの条件は最終headの必須CI、canonical Codex review、独立レビュー、未解決会話0件、対象範囲の許可です。
