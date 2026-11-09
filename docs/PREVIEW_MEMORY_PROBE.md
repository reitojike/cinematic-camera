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

## 公式資料

- [Android MemoryInfo](https://developer.android.com/reference/android/app/ActivityManager.MemoryInfo):
  availMemは確保可能量の保証ではなく、lowMemory/thresholdとmanaged heap上限は別です。
- [LibRaw raw parameters](https://www.libraw.org/docs/API-datastruct-eng.html):
  max_raw_memory_mbはraw buffer制限であり、全プロセスの上限ではありません。
