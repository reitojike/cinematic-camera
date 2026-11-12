# 写真表示 session の合成メモリ計測

Issue #260。通常版の `Original24PreviewSession` を合成DNGだけで通す、明示起動の
instrumentation probeです。製品のメモリ条件、画素処理、保存経路は変更しません。

## 実行

`docs/MANAGED_DEVICES.md` に従い既存ATD固定枠を単独使用します。
SDK/JDK/Gradle cacheと枠の `ANDROID_AVD_HOME` を既存環境に合わせて設定し、
他の高負荷試験と重ねません。実機には実行しません。

```powershell
.\gradlew.bat :app:pixel9aapi37atdDebugAndroidTest --rerun --no-daemon --max-workers=1 `
  "-Pandroid.testInstrumentationRunnerArguments.class=dev.cinematic.p0.jpegrender.original.PreviewMemoryProbeTest" `
  "-Pandroid.testInstrumentationRunnerArguments.isolated=true" `
  "-Pandroid.testInstrumentationRunnerArguments.previewMemoryProbe=true" `
  "-Pandroid.testInstrumentationRunnerArguments.previewMemoryMegapixels=12"
```

12と24を別のテストプロセスで順に実行します。入力は3000×4000と6000×4000、
向き1の未圧縮Bayer合成DNGです。fixtureは行単位生成し、個人写真を参照しません。
通常のテスト実行では明示フラグがなければskipします。

## 区間と出力

- 原本fingerprint生成後のpreflightを別計測。
- 初回exact＋Before、強度変更、色温度/明るさ変更、draft、draft保持後のexact再生成、close。
- warmでは古いBefore/Afterを新ペア取得まで保持します。UI/cacheの追加所有者全体は再現しません。
- 同じ原本hash、sessionの所有資源解放、縮小寸法を検査します。
- `PREVIEW_MEMORY_PART=index/count:...` をGMDの対象test logcatから番号順に連結するとJSONになります。
  3000文字ごとに分け、Androidの1ログ行上限による欠落を避けます。失敗時も収集済み区間を出力します。

既存 `OriginalProbeMetrics` によりmanaged/nativeは約10ms、PSSは約100ms間隔で観測します。
各admission直前と区間境界には `MemoryInfo`、managed/native/PSS、VmRSS/VmHWMを記録します。
heap上限、OSのlow-memory閾値、実RAM、出力寸法もJSONに含めます。

## 解釈の制約

- 観測最大は真のpeak以下になり得ます。managed/native/PSSの別々の最大値を足しません。
- VmHWMはプロセス全体の累積高水位であり、個別区間の最大ではありません。
- fixture生成とfingerprint取得は計測区間外です。GC/JIT/allocator状態と計測自体の負荷は残ります。
  強制GC、largeHeap、OS圧迫用の大量割当は行いません。
- 12MPの縮小入力は768×1024、24MPは1024×682です。warmの差を原本画素数だけの影響と扱いません。
- retainedFieldBytesはGlow field payloadだけで、AS_SHOT/base/一時配列/Bitmap/UI/cacheの総量ではありません。
- 実際の外側の512MiB owner受付、画面描画、実機の低メモリ圧迫・LMK、異なるDNG圧縮/metadataは未検証です。
- 合成試験の成功から768MiB/512MiBの一律引下げ、Pixelでの必要空きRAM、速度・画質合格を導きません。

測定値と採否はIssueに記録し、本書は再現手順を扱います。

## GPU保存Aと次写真Bの同時所有（#260 partial）

`GpuConcurrentMemoryProbeTest` は既存の保存factory・foreground export受付と
`GpuPhotoRollInputs` / `GpuPhotoRollSession` を使用する診断限定の追加観測です。
実Activityやforeground serviceのlifecycleは通さず、明示的に保存ownerを起動します。
合成A/Bは異なる内容で、既存fixtureがAVDのMediaStoreへ作成・照合し、成功後に
自分の検証済み項目だけを回収します。失敗時は既存fixtureの保全規則に従います。

- Aの最初のMIST帯で実GPU textureと全体Bitmapを保持し、Bを開く間だけ待機する。
- Bのsnapshot/FD保持、preflightからnative decode・縮小・入力owner解放、GPU表示、
  A公開後のB保持、最後の解放を既存metricsで観測する。
- Bのnative unpack/process別境界は既存observerから観測できないため、
  `B_SNAPSHOT_FD_HELD_BEFORE_NATIVE_PREFLIGHT` から `B_INPUT_IO_NATIVE_AND_TOKEN_RELEASED`
  までを複合区間として扱う。A自身のnative decodeとB decodeの同時実行はこの試験の対象外。
- 小型取消ケースではBのpreflight前に取消し、Bが解放された後もAが同じrequestで
  完了すること、原本不変、JPEG再読取、texture/GL owner解放を確認する。
- Aの外側受付は既存fixtureの合成条件を使用する。Bの外側受付は現在のMemoryInfo・
  温度・空き容量を読む。内側の768MiB guardとGPU予算は変更しない。

共有AVDはロードマップの1枠を事前調整して単独使用する。初回はSMALLだけを実行する。
12MP/24MPは別途枠・時間を確保し、別プロセスで1条件ずつ実行する。新規AVD作成、
全クラス一括実行、SDK更新、圧迫用割当、強制GC、guard変更は含めない。

```powershell
.\gradlew.bat :app:pixel9aapi37atdGpuDiagnosticAndroidTest --rerun --no-daemon --max-workers=1 `
  -PcinematicGpuDiagnostic=true -PcinematicGpuDiagnosticTests=true `
  "-Pandroid.testInstrumentationRunnerArguments.class=dev.cinematic.p0.jpegrender.original.GpuConcurrentMemoryProbeTest" `
  "-Pandroid.testInstrumentationRunnerArguments.isolated=true" `
  "-Pandroid.testInstrumentationRunnerArguments.gpuExportBoundaries=true" `
  "-Pandroid.testInstrumentationRunnerArguments.gpuConcurrentMemory=true" `
  "-Pandroid.testInstrumentationRunnerArguments.gpuConcurrentMemorySize=SMALL"
```

大判のselectorは同classの `#measureSaveBandAndNextPhoto` に限定し、sizeを `12MP` または
`24MP` にする。取消methodはSMALL専用で、大判class全体の指定は拒否する。
小型の各待機は180秒、大判は900秒で失敗扱いになる。これは実行時間の予測ではない。
予約の暫定目安は小型15分、12MP/24MP各30分（起動・証拠回収を含む）とし、初回結果で見直す。

`GPU_CONCURRENT_MEMORY_PART=index/count:...` を各testごとに連結してJSONを得る。
実行head/APK、AVD image/実RAM/heap、GL環境、条件・結果・skipの有無を別途記録する。
意図的に保持した区間のPSS/RSS・managed/native観測最大は、通常操作の速度・真peak・
必要空きRAM・実機安全性を証明しない。別時刻の最大値は加算しない。
`aBitmapPayloadBytes` と `aRasterDiskBytes` は寸法からの計算値で、RAM実測とは区別する。
GPU台帳はtextureだけを扱い、snapshot・native・Bitmap・driver・surfaceの総量ではない。

既存#264の単独CPU cold/warm結果、#269のguard分離、#355の画素修正、#357の新recipe受付を
この試験で再実装・完了扱いしない。#260は部分対応でopenを維持する。

## 公式資料

- [Android MemoryInfo](https://developer.android.com/reference/android/app/ActivityManager.MemoryInfo):
  availMemは確保可能量の保証ではなく、lowMemory/thresholdとmanaged heap上限は別です。
- [LibRaw raw parameters](https://www.libraw.org/docs/API-datastruct-eng.html):
  max_raw_memory_mbはraw buffer制限であり、全プロセスの上限ではありません。
