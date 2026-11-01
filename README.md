# Cinematic Camera

ライブパフォーマンスの静止画/動画を最優先に、風景・ポートレートでも映画的な色と構図を楽しむAndroidアプリを目指します。
現在の実装はCameraX 1.6.2のRAW/manual基盤と、STILL/CINE・動画/音声の成立性を調べるP0.5 probeです。
アプリ名・packageは仮称です。リポジトリは`reitojike/cinematic-camera`（公開前検査後に承認を得てpublic化）。

[製品方針](specs/002-live-capture/spec.md)と[検証・開発計画](specs/002-live-capture/plan.md)を参照してください。
P0 → P0.5ライブ技術検証 → P1 Stage Clear → P2写真/動画ルック統合 → P3非破壊crop/出力/共有/製品UIの順です。
Stage Dramatic / Cinema T&O、custom編集やLUT入出力は後続です。ルック・編集UIはまだ実装していません。

## Build

- Android SDK Platform 37、Build Tools 36.0.0
- JDK 17以上（本環境はJDK 25）、Gradle wrapper 9.8.1
- AGP 9.4.1の組み込みKotlin + Compose compiler plugin 2.4.21
- CameraXの直接依存はcore / camera2 / lifecycle / view / videoの全て1.6.2に固定

SDKをAndroid Studioで選ぶか、`ANDROID_HOME` / gitignore対象の`local.properties`に指定します。

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`。P0の実機手順・採否判断は[docs/P0_REPORT.md](docs/P0_REPORT.md)を参照してください。

## 実機での操作

許可 → Camera ID選択 → STILLまたはCINEを選びます。STILLはJPEG / RAW / RAW+JPEG → AUTOまたはISO/露光時間(ms)をApply → Capture。
CINEは実対応のquality/range/fps候補と音声を選んでRecord → Stop recording。音声ありのRecord時だけマイク権限を要求します。
起動やmode切替だけでは録画しません。Finalizeと出力処理完了までは次の操作を待ちます。
mode別の受付済み露出を、Interop解除後に復元します。Stop / Startは明示unbind / bindです。背景録画は行いません。

共有保存版の新しいJPEG/DNGは、標準MediaStoreの`Pictures/Cinematic Camera/`へ保存します。
撮影ボタンの下のState / Recent logでURI・名前・byte数を確認できます。JPEGのGoogleフォト等での表示と
DNGの閲覧対応は[最終検証結果](docs/P0_REPORT.md)のユーザー報告と未取得資料を区別してください。
画像本体は再エンコードせずコピーし、広範囲ストレージ権限を追加しません。
API29未満では共有保存は非対応として表示します。ギャラリーやSNSのアプリ内UI、旧写真の移行は作りません。

P0共有保存の基準版は[v0.0.2-dev.1](https://github.com/reitojike/cinematic-camera/releases/tag/v0.0.2-dev.1)です。
新しい検証APKは[Releases](https://github.com/reitojike/cinematic-camera/releases)でtag・source・versionを確認して取得してください。
旧`v0.0.1-dev.1`の内部写真は`files/captures/`にあり、
アプリ削除で失われます。ログは新旧ともprivateの`files/reports/`。ログに画像本体・位置情報・端末シリアルは
記録せず、写真や完全な実機ログはGitへ追加しないでください。署名差と手動更新の注意はReleaseページを参照してください。

権限が恒久拒否された場合はAndroidのアプリ設定で許可してください。操作受付は実測成功ではありません。`ACCEPTED`の後もCaptureResultを比較し、DNGをPCで開くまでは合格扱いにしません。出力の失敗時は部分ファイルが残り得るので、保存成功ログとファイル一覧を照合してください。

MP4は`Movies/Cinematic Camera/`へ保存します。初期値は実対応候補から高解像度/高fpsを優先し、保存済みユーザー希望を尊重します。
公称48MP、4K60、HDR、音声品質を保証せず、要求/適用/実出力とfallback理由を表示します。
[P0.5検証結果と未確認条件](docs/P05_REPORT.md)を参照してください。
Center AF/AE、AUTO時のEV、AF/AWB/照明の能力・実測、OS thermal statusも確認できます。
対応する標準WB preset、MF lens units、AE anti-bandingも操作できます。WB OFF/色gain/Kelvin指定は未実装です。
MFの校正が不明/未校正なら距離メートルへ換算しません。MF中のCenter操作はAUTO AEの測光だけです。
anti-bandingはMANUAL露出中に効果を持たず、LED/PWMの除去を保証しません。
`AUTO reset all`は全Interop・focus要求・EVを解除/neutral化します。mode復帰では対応する保存設定を復元し、
項目の個別変更は他の受付済み制御を保持します。要求・API受付・実CaptureResultを別に比較してください。
APIの合焦結果は画質合格ではなく、SEVERE以上のOS熱状態では録画停止/新規録画抑止を行います。
`Save verification report`はAndroid標準の文書保存画面から、現在の設定・能力・CaptureResultと最大128KiBの
現在セッションlog tailをUTF-8で保存します。写真/動画/音声本体は含めません。録画/Finalize中は実行できません。
省略された過去ログは切断表示し、保存/close失敗では選択文書が部分的な場合があることを示します。
保存先と内容は利用者が確認し、公開Issueへ完全な未確認レポートを自動投稿しません。実機SAF動作は未検証です。
LibRaw / NDK、独自現像、GPU / LUT、21:9出力、共有、ギャラリー、バックエンドは未実装です。

## CIとAPK配布

think-canvasの運用をP0向けに絞った[運用・配布手順](docs/OPERATIONS.md)を参照してください。main初期化後は作業branch / Draft PRを使います。最小CIはbuild / unit / lint、配布は7日間のActions artifactとmainから作る明示tagのpre-releaseです。P0 APKは一時debug署名で、継続更新用の固定署名は未導入です。

マージには最終headの必須CI、GitHub上のcanonical Codex review、独立レビューの明示的OK、未解決thread 0件と作業範囲の許可が必要です。
Renovateの依存更新も同じ条件を適用し、無条件automergeは使いません。CodeRabbit自動レビューはrepo-local設定で停止します。

## 仕様とSpec Kit

[GitHub Spec Kit 1.2.5](docs/SPEC_KIT.md)をCodex skillsで利用します。[P0 spec](specs/001-p0-camera-probe/spec.md)、
[plan](specs/001-p0-camera-probe/plan.md)、[tasks](specs/001-p0-camera-probe/tasks.md)で合意内容・設計・残件を追跡します。
実装済みのチェックとPixel 9a実機ACの合格は別です。インストールとカメラ操作にはユーザー報告がありますが、
実機の詳細証拠と追加条件の確認は[Issue #4](https://github.com/reitojike/cinematic-camera/issues/4)で管理します。
P1判断案は制約付きGoであり、新フェーズ実装の承認とは別です。
