# 原寸DNGの診断（#143）

通常のカメラアプリとは別の `dev.cinematic.rawprobe` を使う、debug限定の手動診断です。
既存アプリの署名・データを置き換えません。Activity、撮影・通信・共有ストレージ権限はありません。
12MP（4000×3000）と24MP（6000×4000）の合成Bayer DNGを診断アプリのcacheに生成し、終了時に削除します。
実写の原本やMediaStoreは読みません。診断APKをReleaseや固定署名版として配布しません。

## 実行

Android CIの `cinematic-raw-probe-debug` artifactには、診断APK、テストAPK、この説明とPowerShell helperを含めます。
展開したディレクトリでADBの対象を明示します。実機は本人が操作し、他の作業中の端末・AVDには実行しません。

```powershell
# 最初はRAM/通常heapポリシーのみ。YOUR_DEVICE_SERIALは手元で置き換え、共有レポートに含めない。
.\Invoke-RawProbe.ps1 -Serial YOUR_DEVICE_SERIAL -AllowPhysical -MemoryOnly
# 次に、他の重い処理を止め、撮影せず、十分な空き容量と通常の温度で合成原寸decodeを実行。
.\Invoke-RawProbe.ps1 -Serial YOUR_DEVICE_SERIAL -AllowPhysical
```

APKの署名不一致やインストール失敗時は停止します。カメラアプリや旧診断アプリを自動削除しません。
失敗時も勝手にlargeHeapを有効化したり、上限を増やして再試行したりしません。
実機で操作する場合は発熱・操作不能・OSのメモリ不足があれば中止し、再開は端末が回復してから判断します。
レポートは実行元の `.android/rawprobe-results/` に保存します。Gitへ追加しません。
終了後、診断アプリだけをAndroid設定から削除してもカメラアプリのデータには影響しません。

ソースからは `:rawprobe:lintDebug :rawprobe:assembleDebug :rawprobe:assembleDebugAndroidTest` でビルドします。
helperの `-ApkDirectory` には2つのAPKを置いたディレクトリを指定します。通常アプリのAPKは使いません。

## 数値の意味と限界

- `memory-policy.json`: Androidの総RAM/空きRAM、memoryClass、largeMemoryClass、Runtime.maxMemory、通常heap使用量、native割当、PSS、温度状態。
  largeMemoryClassは設定照会だけです。manifestにlargeHeapはなく、その容量を利用した試験ではありません。
- `full-resolution.json`: 原寸16bit線形sRGBのnative decode。half-sizeを無効化し、出力寸法・バイト数と90度回転した四隅の画素を確認します。
  非暗号学的な出力checksumは診断用です。原本保全は別途SHA-256の前後一致と読み取り専用FDで確認します。
- `boundaries.json`: 開始前/処理前キャンセル、64MiBファイル/24MP画素の固定上限、不正入力、その後の小さい正常decodeの回復。
  native割当上限は512MiBに固定します。これはLibRawの内部制限で、プロセス全体の上限ではありません。
- 各段階の時刻とメモリ、10ms間隔のmanaged/native・100ms間隔のPSSの**観測最大値**を記録します。
  完全なピークではなく、サンプリング自体の負荷も含みます。elapsedはFD操作・計測を含み、前後のSHA-256計算は除きます。
  RSSのHWMは各decode単体ではなく、診断process起動後の累積最大値です。

診断は独立した軽いプロセスです。カメラアプリ稼働中の実メモリや撮影優先・キュー制御を再現しません。
Stage Clear、顔保護、JPEG生成・保存を含む12MP/24MP対応、色の正確性や実写の画質を証明しません。
通常アプリの1024px/16MP制限や保存・公開条件は変更しません。外部カメラの任意の24MP RAW対応も保証しません。
Pixel 9aの実測とAVDの測定は別です。AVDへのRAM指定だけでアプリのheap上限が増えるとは限りません。
実機の通常アプリのPSSを別途観察する場合は、読み取り専用の `adb shell dumpsys meminfo dev.cinematic.p0` を使い、
この診断プロセスの値と混ぜません。共有する際はJSONだけを確認し、ADBのserialや端末固有情報を加えません。
