# Gradle Managed Deviceによる自動検証

Issue #190、#192。タスクごとの手動AVD作成を止め、Google公式の
[Gradle Managed Devices](https://developer.android.com/studio/test/managed-devices)を利用する。
製品仕様、実機AC、必須CIは変更しない。

## 固定構成と再利用

`app/build.gradle.kts` の `pixel9aapi37`（Pixel 9a / API 37 / Google APIs）を共通で使う。
AGP 9.5.1 / sdklib 32.5.1同梱の公式Pixel 9a profileは1080×2424、論理density 420dpi、
RAM 7556MiBを定義する。画面全体の目安は約411×923dp（system bar領域を除く前）。
製品仕様の6.3inch / 8GBとprofileの6.24inch / 7556MiBは一致する表記ではなく、
この構成での生成configは2G、初回起動のhardware-qemu.iniは4096MiBだった。profileの7556MiBを実割当とみなさず、起動後の実RAM割当を確認する。独自のconfig書換えでRAMを強制しない。
Windows x86_64のsystem imageを使うためTensor G4 / GPU / camera / 実機性能は再現しない。
API 37のGoogle APIs imageと組み合わせ、公開DSLの `sdkVersion` を使用する。

同じprofileで、公式の[Automated Test Device（ATD）](https://developer.android.com/studio/test/managed-devices#gmd-atd)の
`pixel9aapi37atd`（`systemImageSource = "google-atd"`）も定義する。用途で使い分ける:

| 端末 | 使う試験 | 制約 |
|---|---|---|
| `pixel9aapi37atd`（既定） | 合成データのみを使い、SystemUI・IME・Google標準アプリに依存しないCompose/View回帰 | SystemUI・Settings・IME・多くのGoogleアプリが無く、hardware renderingが無効。`uiAutomation.takeScreenshot()` / `screencap` / `PixelCopy` は内容を取得できない |
| `pixel9aapi37` | SystemUI・通知・IME・権限ダイアログ・Play services、または表示合成結果の取得が必要な試験 | 2 vCPUの割当に対して起動直後やsnapshot復帰直後の標準アプリ負荷が大きく、OS側ANRダイアログが試験画面を覆うことがある |

ATDで画素を判定する試験は、表示のキャプチャではなく、対象Windowの `decorView.draw(Canvas(bitmap))`
（ソフトウェア描画）で読む。これはViewツリーの描画内容を検証するもので、表示合成・GPU経路は検証しない。
`Bitmap.Config.HARDWARE` の画像はソフトウェアCanvasに描けないため、実画像を表示する試験では
デコード設定を確認してから使う。

既存のAndroidJUnitRunnerとandroidTestを利用し、独自AVD作成スクリプト、quarantine、shardは追加しない。
AGPがデバイスの作成・起動・テスト・終了を管理する。再利用するのは同じ構成とAGPの共有キャッシュであり、
毎回同じ起動済み端末やテストで変更した状態が残ることには依存しない。

- 同じホストのworktreeでは同じSDK、`GRADLE_USER_HOME`、`ANDROID_AVD_HOME` を使う。タスク名入りのAVDやキャッシュを作らない。
- GradleキャッシュとAVD実体は別の保存先。AGP 9.5.1ではAVD実体は `ANDROID_AVD_HOME/gradle-managed`（AVD home未指定時は `ANDROID_USER_HOME/avd/gradle-managed`）へ置かれる。`GRADLE_USER_HOME` の指定だけではAVDを移せない。
- [Android公式環境変数](https://developer.android.com/tools/variables)を用い、プロセス内で共通開発ドライブの `ANDROID_AVD_HOME` を明示する。指定前に共通AVDディレクトリを作成し、存在を確認する。未作成パスはSDK側で無視され既定先へfallbackし得るため、初回setupで生成先も確認する。既存 `ANDROID_USER_HOME` / adb認証状態は変更しない。ユーザー固有の絶対パスはGitへ保存せず、グローバル設定も変更しない。
- 作業checkout/build/logは開発用ドライブ配下へ置く。SDK/JDKの移設や既存AVDの削除は別作業とする。
- 複数workerのGMDはホスト単位で逐次実行する。`--max-workers=1` だけでは別Gradleプロセス同士を排他しない。
- 初回のsystem image取得、SDK license、仮想化環境が必要ならその条件を確認してから進める。独自回避はしない。
- `cleanManagedDevices` は共有資源の再生成につながるため、通常実行には含めない。

## 実行

JDK/SDK/共有Gradleキャッシュを既存開発環境で設定したうえで、リポジトリrootで実行する。
`ANDROID_AVD_HOME` は全worktree共通の開発ドライブ上の既存ディレクトリへ指定する。
PowerShellでは、正しい共通パスを設定済みであることを確認してから実行する:

```powershell
if (-not $env:ANDROID_AVD_HOME) { throw "Set the shared development-drive AVD directory first" }
New-Item -ItemType Directory -Path $env:ANDROID_AVD_HOME -Force | Out-Null
if (-not (Test-Path -LiteralPath $env:ANDROID_AVD_HOME -PathType Container)) { throw "Shared AVD directory is missing" }
```

初回setupでは `ANDROID_AVD_HOME/gradle-managed` の生成configとhardware-qemu.iniの実data pathを確認する。
意図しない既定先への生成を発見したら、その実行だけを止めて保存先を修正する。

まずは端末を起動しないtask確認:

```powershell
.\gradlew.bat :app:pixel9aapi37atdDebugAndroidTest --dry-run --no-daemon --max-workers=1
```

合成画像のみを使う比較gesture回帰（実カメラ撮影・実RAW・共有保存はしない）:

```powershell
.\gradlew.bat :app:pixel9aapi37atdDebugAndroidTest --rerun --no-daemon --max-workers=1 `
  "-Pandroid.testInstrumentationRunnerArguments.class=dev.cinematic.p0.photoroll.PhotoRollComparisonGestureAndroidTest" `
  "-Pandroid.testInstrumentationRunnerArguments.isolated=true"
```

`isolated=true` はこのGMDで合成テストを実行するときだけ指定する。実機の安全確認を省略するためには使わない。
既存androidTestには実入力・追加引数・高負荷benchmarkを前提とするものもあるため、
初回から全クラスを一括実行せず、試験ごとの前提を読んで対象classを選ぶ。

入力・APKが前回と同じ場合、GradleはテストtaskをUP-TO-DATEとして端末を起動しない。
再実行が目的なら `--rerun` を付け、UP-TO-DATEの回を実行結果として扱わない。

`pixel9aapi37` でheadless環境のGPUが使用できない場合は公式の
`"-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect"` を追加する。
GPU指定は実際に `hardware-qemu.ini` の `hw.gpu.mode` で確認する。Windowsでは `auto-no-window` や
`angle_indirect` を指定してもCPU描画（lavapipe）になることがあり、ホストGPUを使うのは `host` のみである。
`host` はホストのGPUドライバ側の障害でエミュレーターごと終了し得るため、既定にはしない。
ホストの仮想化はAndroid公式の推奨どおりWHPXを使う（AEHDは2026-12-31に提供終了）。

ANRや負荷を調べるため `logcat -b all` を常時取得すると、adbd・logcat・logdがゲストCPUを大きく使い、
負荷を押し上げる。必要な期間・bufferに限定する。snapshot復帰後のlogcat bufferには生成時の記録が残るため、
復帰後のsessionの事象と区別する。
HTML reportの場所はGradleの出力を確認する。task解決、APK build、端末setup、実テストの結果を分けて記録し、
未実行・skip・環境失敗を合格扱いしない。

## 検証の境界

GMDは自動instrumentation用であり、対話的なUI確認用の常設エミュレーターを提供する仕組みではない。
手動の視覚確認が必要な場合は自動UI試験で代替可能か確認し、対話端末が不可欠なら別途相談する。既存手動AVDは保全のみとし、この運用では起動しない。
合成UI・権限・Android APIの回帰と、Pixel実機のcamera/RAW能力・24MP保存・発熱・速度は別の証拠とする。
GMD成功を実機ACや性能の合格に読み替えない。CIへのGMD追加や必須チェック変更はこの変更に含めない。
