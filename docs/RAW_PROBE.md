# 原寸DNGの診断（#143）

通常のカメラアプリとは別の `dev.cinematic.rawprobe`（通常）と `.largeheap`（largeHeap）を使う、debug限定の手動診断です。
既存アプリの署名・データを置き換えません。Activity、撮影・通信・共有ストレージ権限はありません。
従来のnative decode診断は12MP（4000×3000）と24MP（6000×4000）の合成Bayer DNGをcacheに生成し、テスト終了時に削除します。
追加の[基準JPEG診断](BASELINE_JPEG_PROBE.md)はAVD専用で、合成DNGと途中JPEGを検査用に保全します。どちらも実写真は扱いません。
実写の原本やMediaStoreは読みません。診断APKをReleaseや固定署名版として配布しません。

## 実行

Android CIの `cinematic-raw-probe-debug` artifactには、通常/largeHeapそれぞれの診断APK・テストAPK、この説明とPowerShell helpersを含めます。
基準JPEG診断の説明とPython helperも同梱します。既存PowerShell helperはこの新試験を自動実行しません。
展開したディレクトリでADBの対象を明示します。実機は本人が操作し、他の作業中の端末・AVDには実行しません。

```powershell
# 最初はRAM/通常heapポリシーのみ。YOUR_DEVICE_SERIALは手元で置き換え、共有レポートに含めない。
.\Invoke-RawProbe.ps1 -Serial YOUR_DEVICE_SERIAL -AllowPhysical -MemoryOnly
.\Invoke-RawProbe.ps1 -Serial YOUR_DEVICE_SERIAL -AllowPhysical -MemoryOnly -LargeHeap
# 他の重い処理を止め、撮影せず、空き容量と温度を確認して同じ1024px演算を比較。
.\Invoke-RawProbe.ps1 -Serial YOUR_DEVICE_SERIAL -AllowPhysical -RenderOnly -RenderEdge 1024
.\Invoke-RawProbe.ps1 -Serial YOUR_DEVICE_SERIAL -AllowPhysical -RenderOnly -RenderEdge 1024 -LargeHeap
# 12/24MPのnative原寸decodeだけを別に測定。
.\Invoke-RawProbe.ps1 -Serial YOUR_DEVICE_SERIAL -AllowPhysical -NativeOnly
```

通常/largeHeapの各プロセスはinstrumentation開始時に起動します。[AndroidのlargeHeap設定](https://developer.android.com/guide/topics/manifest/application-element#largeHeap)であり、処理中だけheap上限を切り替える仕組みではありません。
largeHeapの採用を製品へ反映する変更はありません。両条件の `maxHeapBytes` と `largeHeapRequested` を確認して比較します。
同じ温度・他アプリ負荷で、1回目を初回、続く2回をウォームとして全件残します。結果を見て本人が判断した後に限り、
`-RenderEdge 1536`、次に `2048` を**手動で**試せます。自動で大きいサイズへ進みません。12/24MPの現像演算は実行しません。
nativeもlargeHeap条件で比べる場合は `-NativeOnly -LargeHeap` を使い、別工程の最大値を足して全工程のピークにしません。

APKの署名不一致やインストール失敗時は停止します。カメラアプリや旧診断アプリを自動削除しません。
失敗時も勝手にlargeHeapへ切り替えたり、上限を増やして再試行したりしません。失敗したrunも結果を先に回収し、古いJSONを今回の証拠にしません。
実機で操作する場合は発熱・操作不能・OSのメモリ不足があれば中止し、再開は端末が回復してから判断します。
レポートは実行元の `.android/rawprobe-results/` に保存します。Gitへ追加しません。
終了後、診断アプリだけをAndroid設定から削除してもカメラアプリのデータには影響しません。

ソースからは `:rawprobe:lintNormalDebug :rawprobe:assembleNormalDebug :rawprobe:assembleNormalDebugAndroidTest` と、
同じ3タスクの `NormalDebug` を `LargeHeapDebug` へ替えてビルドします。helperの `-ApkDirectory` にはCIと同じ名前で4 APKを置きます。
通常appのAPKは使いません。診断のrelease variantsは生成しません。

## 数値の意味と限界

- `memory-policy.json`: Androidの総RAM/空きRAM、memoryClass、largeMemoryClass、Runtime.maxMemory、通常heap使用量、native割当、PSS、温度状態。
  largeMemoryClassは設定照会です。通常診断のlargeHeapはfalse、別packageのlargeHeap診断だけがtrueです。
- `render-memory.json`: 現行のR7/Stage Clearソースで合成入力を3回演算。入力生成・checksum・計測を含み、DNG/JPEGや本体UIは含みません。
  faceDetectionはNOT_RUNです。失敗はMANAGED_MEMORYとして記録してJUnitも失敗させ、次のサイズへ進みません。
- `full-resolution.json`: 原寸16bit線形sRGBのnative decode。half-sizeを無効化し、出力寸法・バイト数と90度回転した四隅の画素を確認します。
  非暗号学的な出力checksumは診断用です。原本保全は別途SHA-256の前後一致と読み取り専用FDで確認します。
- `boundaries.json`: 開始前/処理前キャンセル、64MiBファイル/24MP画素の固定上限、不正入力、その後の小さい正常decodeの回復。
  native割当上限は512MiBに固定します。これはLibRawの内部制限で、プロセス全体の上限ではありません。
- 各段階の時刻とメモリ、10ms間隔のmanaged/native・100ms間隔のPSSの**観測最大値**を記録します。
  完全なピークではなく、サンプリング自体の負荷も含みます。elapsedはFD操作・計測を含み、前後のSHA-256計算は除きます。
  RSSのHWMは各decode単体ではなく、診断process起動後の累積最大値です。

診断は独立した軽いプロセスです。カメラアプリ稼働中の実メモリや撮影優先・キュー制御を再現しません。
debugビルドの計測であり、配布Releaseの速度を表すものでもありません。
Stage Clear、顔保護、JPEG生成・保存を含む12MP/24MP対応、色の正確性や実写の画質を証明しません。
通常アプリの1024px/16MP制限や保存・公開条件は変更しません。外部カメラの任意の24MP RAW対応も保証しません。
Pixel 9aの実測とAVDの測定は別です。AVDへのRAM指定だけでアプリのheap上限が増えるとは限りません。
実機の通常アプリのPSSを別途観察する場合は、読み取り専用の `adb shell dumpsys meminfo dev.cinematic.p0` を使い、
この診断プロセスの値と混ぜません。共有する際はJSONだけを確認し、ADBのserialや端末固有情報を加えません。

## 既存カメラアプリの実際の操作を観察

次のhelperはAPKの導入・アプリ設定の変更・写真の読出し・撮影操作をしません。本人が既存appを開き、同じ写真を現像します。

```powershell
.\Measure-CameraMemory.ps1 -Serial YOUR_DEVICE_SERIAL -Seconds 60
```

約1秒間隔の `dumpsys meminfo` からmanaged/native/graphics/totalなどのメモリ集計行だけをJSONへ残します。
操作開始→表示完了の秒数、エラー・終了・発熱・撮影へ戻れるかは本人が記録します。端末内の写真や画面は回収しません。
これは通常appの実測補助であり、largeHeap診断の演算と同じ全工程比較ではありません。サンプル間のピークや正確な現像時間も保証しません。
