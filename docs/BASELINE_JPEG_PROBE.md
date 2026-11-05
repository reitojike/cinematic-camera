# 合成DNGから基準JPEGを作る診断（#143の段階B）

**AVD専用のdebug診断です。実写・Pixel実機・Stage Clear/R7・製品FULLの検証ではありません。**
既存のカメラアプリや固定署名を置き換えず、診断packageの私有cacheに合成DNG/JPEGを生成します。
保存・公開・自動現像・カメラロールへ接続しません。製品の16MP入力/1024px/half-size/128MiBと通常heap設定を維持します。

## 経路と固定上限

LibRaw 0.22.3の通常出力APIで原寸RGB16を作り、nativeでsRGB転送を1回適用してRGBA8のsoftware Bitmapへpackします。
標準`Bitmap.compress(JPEG, 95)`でCREATE_NEW出力し、write/flush/実close後にencoder Bitmapを解放してから原寸codec readbackします。
書込成功byteとfresh readbackのSHA-256、寸法/sRGB/追加回転なし、原本前後hashを確認します。

- small入口: 入力128KiB・格納4096画素・各辺128以下。独立した全画素比較用の小型RGB16 oracleを返します。
- large入口: 入力と出力64MiB・格納24,000,000画素・各辺8192以下、LibRaw max_raw_memory_mb=512。RGB16 oracleは空です。
- classic little-endian TIFF / single-frame 3色Bayer / 明示された整数ActiveArea・DefaultCrop / square pixelsだけを扱う限定経路です。
  user crop・小数crop・不明/範囲外geometryを推測で成功にしません。格納画素に余白を含めて24MPを超える入力も拒否します。
- 大画像の原本/出力hashは4KiB streamingです。JPEG全ByteArrayやRGB FloatArrayを作りません。
  LibRaw本体とmake_mem_imageの全画面native割当ては残るため、ストリーミングRAW現像ではありません。
- 実画素は正立済みです。標準encoderのJPEGにEXIF方向tagはなく、Android readerの観測値0と画素方向1を別記録します。
  EXIF 1 tagを書いたとは主張せず、追加の方向2〜8を拒否します。
- writer/reader/FDのcloseが不確実なら実ownerを保持します。明示close確認は再renderではなく、失敗・取消からreceiptを作りません。
  成功receiptも`DIAGNOSTIC_BASELINE_NO_R7_NO_STAGE_CLEAR`、face/EVはNOT_RUNであり製品の成功証跡ではありません。

## AVDでの再実行

専用の使用していないAVDを本人が用意します。他作業のAVDやプロセスは停止しません。
CI artifactには4つの診断APK、`Measure-BaselineJpeg.py`とこの説明を含めます。
このPython helperはWindows / Python 3.12以上の標準ライブラリだけを使い、APK導入やAVD起動は行いません。
カメラアプリのAPKを使わず、対象AVDを確認して通常版の2 APKだけを導入します。

```powershell
# YOUR_AVD_NAMEとemulator-5554は自分の専用AVDへ置き換えます。実機serialは使用できません。
adb -s emulator-5554 emu avd name
adb -s emulator-5554 install -r .\rawprobe-debug.apk
adb -s emulator-5554 install -r .\rawprobe-debug-androidTest.apk
# 小型のcrop/8方向/全画素/故障回帰とlargeのstreaming回帰（計9件）
adb -s emulator-5554 shell am instrument -w -e rawProbe true -e class dev.cinematic.rawprobe.BaselineSmallTest,dev.cinematic.rawprobe.BaselineLargeSmokeTest dev.cinematic.rawprobe.test/androidx.test.runner.AndroidJUnitRunner
# まず12MPのsuccessだけ。結果確認後にcaseや寸法を手動で変えます。
python .\Measure-BaselineJpeg.py --adb adb --serial emulator-5554 --avd YOUR_AVD_NAME --mp 12 --case success --flavor normal --output .android/baseline-jpeg
```

署名不一致/導入失敗なら停止し、古い診断appやカメラappを自動削除しません。
12MPの後、`--case write` / `close` / `cancel`を1件ずつ実行できます。次に`--mp 24`へ手動で進みます。
各caseは独立したinstrumentationで、失敗時に自動再試行・上限変更・largeHeap切替をしません。
largeHeap比較時は同flavorの2 APK（`rawprobe-largeheap.apk` / `rawprobe-largeheap-androidTest.apk`）を別packageとして導入し、
helperに`--flavor largeHeap`を指定します。`-AllowPhysical`のような実機overrideはありません。
既存の`Invoke-RawProbe.ps1`はこの新試験を自動実行しません。

開始にはhost空きphysical4GiB/commit2GiB以上、端末側lowMemory=false・空き1GiB・cache空き256MiB以上を要求します。
AVD起動前はさらに大きい余裕を取り、他の重いビルドと順番に実行します。helperは稼働中commit1GiB未満/600秒超過で、
起動済みの診断packageだけを同AVD再確認後にbest-effort停止します。local adbもbounded waitで回収します。
停止観測はclose ack/成功にせずUNKNOWNです。hostの他app・写真・AVD設定には触れません。

## 結果の読み方と保全

helperが固有32桁runIdを生成し、端末は`baseline-RUN_ID.json`へ開始状態をCREATE_NEWで記録します。
完了後のJSON/logを同runId・case/MP/package・complete・JUnit OKで照合し、JSONのSHAも回収記録に残します。
セットアップ失敗・OS終了・旧JSON・欠落は成功にしません。fault caseのJUnit OKは「期待した失敗の保護」を意味します。

`elapsedMs`は原本hash、decode/pack、encode、write/flush/close、readback hash/原寸codec/EXIF、原本再hashと計測負荷を含みます。
行単位fixture生成と計測前fingerprintは除外します。従来のnative単体診断とは計測範囲が異なります。
managed/nativeはnominal10ms、PSSは100msの観測最大です。真のpeak上限ではなく、別時刻の値を足して総メモリにしません。
段階snapshot、processの累積RSS high-water、実heap上限も別フィールドです。

合成DNG・失敗途中JPEGは私有cacheに保全します。runtime画像はGitやCI artifactへ回収しません。
host結果はGit管理外の`.android/`へ置きます。診断appのAndroid設定による削除で私有合成cacheも除去できますが、
不確実closeがある間は削除せず、成功/失敗の記録を先に保全します。本人判断で行い、自動削除しません。

## 取得済みの合成AVD測定

main `c0ba175`を基点としたローカル候補、API37/x86_64/debug、RAM設定4096MiB・2 cores・software GPU。
normalの実max heap192MiB、largeHeap576MiB。各successは1回でcold/warm/他作業負荷を統制した性能比較ではありません。
その後main `c5e1a4f`（強度UI・保存保護・依存更新を含む）へ取り込み、同じnative/fixture/attemptソースのビルドをCIで再検証します。
下表は**取り込み前の実行結果**であり、現在headのAPKを新たに実行した結果ではありません。

|出力|heap|時間秒|managed観測最大MiB|native観測最大MiB|PSS観測最大MiB|
|---|---|---:|---:|---:|---:|
|3000×4000（格納4000×3000/EXIF6）|normal|12.071|3.48|212.13|227.05|
|同上|largeHeap|8.278|3.39|212.14|227.70|
|6000×4000（EXIF1）|normal|13.492|3.36|418.20|395.21|
|同上|largeHeap|13.192|3.39|418.21|382.49|

成功4件＋normal各寸法のwrite/close/pack取消6件＝10runで、原本不変・最終owner解放を確認しました。
close例外直後はreleased=false、明示close後にtrueです。同寸法のnormal/largeHeap JPEGは全byte一致し、
12MPは3,626,384bytes、24MPは7,184,578bytes。実JPEGはローカルに保全して独立codecでも全画像を読みました。
小型回帰は各heap9件、製品decoder回帰は7件成功。大画像のRGB値の独立全画素比較は行っていません。
故障も含めたPSS観測最大は12MP約235.1MiB / 24MP約433.6MiBです。
host資源ガードで次の24MP closeが1回未開始となり、専用AVD終了・他ビルド終了待ち後、同設定で残りを実行しました。
稼働中timeout/強制停止分岐の故障実行は未実施です。

### 12〜13秒の内訳

同じnormal success runの段階snapshot時刻差から算出した値です。計測負荷を含む区間で、純粋な関数CPU時間ではありません。

|区間|12MP 秒|24MP 秒|
|---|---:|---:|
|入力snapshot等|0.315|0.300|
|LibRaw open/metadata・unpack/crop等|0.232|0.241|
|dcraw_process（AS_SHOT、demosaic/基準色変換等）|6.411|7.442|
|原寸RGB16出力生成・RAW資源解放・Bitmap確保等|1.322|0.584|
|sRGB/RGBA全画素pack・出力資源解放等|2.990|3.905|
|JPEG圧縮・write/flush/close（書込SHA含む）|0.221|0.370|
|readback hash・bounds/原寸codec・EXIF等|0.252|0.339|
|原本hash・区間間の後始末/計測残差|0.328|0.311|
|合計|12.071|13.492|

この範囲ではJPEGファイル化より、その前のRAW処理/画素変換が支配的でした。
圧縮とファイルIOは同一区間なので、両者の個別時間は分離していません。
R7/Stage Clear、明るさ/色味/強度編集の反映時間は含みません。DNGを毎回開き直す原寸診断であり、
調整値を変えるたびに12〜13秒かかるという測定ではありません。縮小プレビューの操作待ち時間や
decoded土台のキャッシュ採否は別の経路/計測で確認します。

これは原寸基準JPEG候補の成立確認です。nativeメモリは約212→418MiBへ増えており、
512MiBはLibRaw内部の設定でプロセス全体の上限ではありません。largeHeapの製品採用判断でもありません。
Pixel/実ARM64・実写の色/画質・R7/Stage Clear/顔/EV・熱/長時間安定性・撮影応答・製品保存/公開・
メーカー/圧縮別互換、格納24MPを超える余白付きDNGは未検証です。#143/#78/#101はこの結果だけで閉じません。
