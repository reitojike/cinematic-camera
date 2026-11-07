# Gradle Managed Deviceによる自動検証

Issue #190。タスクごとの手動AVD作成を止め、Google公式の
[Gradle Managed Devices](https://developer.android.com/studio/test/managed-devices)を利用する。
製品仕様、実機AC、必須CIは変更しない。

## 固定構成と再利用

`app/build.gradle.kts` の `pixel2api37`（Pixel 2 / API 37 / Google APIs）を共通で使う。
既存のAndroidJUnitRunnerとandroidTestを利用し、独自AVD作成スクリプト、quarantine、shardは追加しない。
AGPがデバイスの作成・起動・テスト・終了を管理する。再利用するのは同じ構成とAGPの共有キャッシュであり、
毎回同じ起動済み端末やテストで変更した状態が残ることには依存しない。

- 同じホストのworktreeでは同じSDK、`GRADLE_USER_HOME`、`ANDROID_AVD_HOME` を使う。タスク名入りのAVDやキャッシュを作らない。
- GradleキャッシュとAVD実体は別の保存先。AGP 9.5.1ではAVD実体は `ANDROID_AVD_HOME/gradle-managed`（AVD home未指定時は `ANDROID_USER_HOME/avd/gradle-managed`）へ置かれる。`GRADLE_USER_HOME` の指定だけではAVDを移せない。
- [Android公式環境変数](https://developer.android.com/tools/variables)を用い、プロセス内で共通開発ドライブの `ANDROID_AVD_HOME` を明示する。既存 `ANDROID_USER_HOME` / adb認証状態は変更しない。ユーザー固有の絶対パスはGitへ保存せず、グローバル設定も変更しない。
- 作業checkout/build/logは開発用ドライブ配下へ置く。SDK/JDKの移設や既存AVDの削除は別作業とする。
- 複数workerのGMDはホスト単位で逐次実行する。`--max-workers=1` だけでは別Gradleプロセス同士を排他しない。
- 初回のsystem image取得、SDK license、仮想化環境が必要ならその条件を確認してから進める。独自回避はしない。
- `cleanManagedDevices` は共有資源の再生成につながるため、通常実行には含めない。

## 実行

JDK/SDK/共有Gradleキャッシュを既存開発環境で設定したうえで、リポジトリrootで実行する。
まずは端末を起動しないtask確認:

```powershell
.\gradlew.bat :app:pixel2api37DebugAndroidTest --dry-run --no-daemon --max-workers=1
```

合成画像のみを使う比較gesture回帰（実カメラ撮影・実RAW・共有保存はしない）:

```powershell
.\gradlew.bat :app:pixel2api37DebugAndroidTest --no-daemon --max-workers=1 `
  "-Pandroid.testInstrumentationRunnerArguments.class=dev.cinematic.p0.photoroll.PhotoRollComparisonGestureAndroidTest" `
  "-Pandroid.testInstrumentationRunnerArguments.isolated=true"
```

`isolated=true` はこのGMDで合成テストを実行するときだけ指定する。実機の安全確認を省略するためには使わない。
既存androidTestには実入力・追加引数・高負荷benchmarkを前提とするものもあるため、
初回から全クラスを一括実行せず、試験ごとの前提を読んで対象classを選ぶ。

headless環境でGPUが使用できない場合は公式の
`"-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect"` を追加する。
HTML reportの場所はGradleの出力を確認する。task解決、APK build、端末setup、実テストの結果を分けて記録し、
未実行・skip・環境失敗を合格扱いしない。

## 検証の境界

GMDは自動instrumentation用であり、対話的なUI確認用の常設エミュレーターを提供する仕組みではない。
手動の視覚確認が必要な場合は自動UI試験で代替可能か確認し、対話端末が不可欠なら別途相談する。既存手動AVDは保全のみとし、この運用では起動しない。
合成UI・権限・Android APIの回帰と、Pixel実機のcamera/RAW能力・24MP保存・発熱・速度は別の証拠とする。
GMD成功を実機ACや性能の合格に読み替えない。CIへのGMD追加や必須チェック変更はこの変更に含めない。
