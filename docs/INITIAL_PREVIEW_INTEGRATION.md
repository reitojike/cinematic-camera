# 初回案内表示の製品接続候補

Refs #233 / #241。最新ユーザー判断を基準に実装中。#442 の main `f2c94cc0` を統合済み。画面比較・実走・最終レビューは未完了で、採用/マージ済みを意味しない。exact は FULL AHD、原寸保存は不変。#438/#441 の PPG 判定には依存しない。

## 候補と根拠

| 優先 | ローカル候補の実装 | 制約 |
| --- | --- | --- |
| 保存済みcache | 照合済み保存JPEGの縮小像だけを1枚保持。hit時も原本/recipe/geometry/公開記録/JPEG SHAを再照合 | 新GPU readbackなし。未保存GPU編集のcacheではない |
| 保存済みJPEG | 正規publication v3/PUBLISHED、cursorと世代intent、完全recipe・既知renderer・原本SHA・geometry/fullCoverage・公開所有行・JPEG SHA/headerを照合 | read-only baseのみ、AtomicFile recovery/保存queue/世代作成/公開を使わない。未知・欠損・不一致はmiss |
| embedded JPEG | 同一照合済snapshot FDからLibRaw thumbs_listを得て直接slice。8MiB、4096辺/4MP、原本未回転寸法以下、range/SOI/EOI/SOS構造と明示向きを検査 | JPEG自身の向きでFit。RAW default cropとの画角/色一致を保証しない |
| half案内像 | 同じsnapshot FD、共有1枠の同期hook内で一度だけHALF_CANDIDATE、raster解放後にFULL AHDへ | exactの入力へ流用しない。追加遅延の採否は対照計測・本人判断前 |

saved JPEGのcrop座標が明示保存されていないことだけを理由に全件missとはしない。既知の整数DNG契約では、同じ原本bytesの一意CFA IFD/ActiveArea/DefaultCropOrigin/Sizeからcropを導出できる。履歴監査で既知contractと原本/寸法/向き/fullCoverageを照合する限定根拠を確認した。未知contractは推測しない。新manifestや保存codecは追加しない。

## snapshot / native / memory

`Original24PreviewSession` が作成・fingerprint照合したread-only FDを同期hookに貸し、guide処理が戻ってから同FDでFULLを実行する。入力IO workerと既存共有tokenを保持し、opaque native退出/cleanup確認前に解放しない。取消/half失敗を握りつぶしてFULLへ進むことは禁止。失敗したcloseは既存ownerで保持する。原本事後照合とFULL geometry検査は維持。

抽出器は `unpack_thumb_ex` を使わずsnapshot範囲を直接コピーする。既存appのLibRaw raw targetを再利用し、共通macro・dng_decoder本体は不変。入力64MiB、JPEG8MiB上限を維持する。旧実装もunpack呼出前に8MiB検査済みで、既定512MiBをそのまま確保していたという意味ではない。

LibRaw constructor flags/progress取消と `max_raw_memory_mb=512` は現行Original24経路に合わせる。WB metadataを捕捉するAsShot callbackはJPEG抽出には登録しない。先行preflightはcallback登録とgeometry検証を行うがWB意味検証前に戻るため、WBの正しさを検証済みとはしない。FULLが従来WB検証と原本事後照合を行う。全設定完全一致やmetadata累積hardcapは主張しない。2026-11-15の本人判断により、旧総hardcap必須条件をこの限定条件へ更新した。

JPEG完全entropy検出は要求しない。構造検査と標準codecが報告するエラー拒否は維持するが、回復可能なentropy警告は成功し得る。新codec依存・独自entropy parserなし。0.02 byte/pixel等の極端下限はPixel/SIGMA fpの承認済実データ未取得につき未採用。

各Bitmapは長辺1024以下/ARGB8888/最大4MiB。decode回転中は2枚、cache専有は1枚、現requestのUI公開は1枚。halfは最大1024のlinear入力と表示変換IntArrayを一時使用する。原寸Bitmapは作らない。cacheコピー/guide処理とFULL開始前に既存OS memory admissionを再確認する。これらは明示所有と配置上限であって、退場中Compose/RenderThreadやGC待ちを含むプロセス総量の厳密capではない。反復切替の保持/実メモリ観測を別途行う。

## 表示と寿命

photoroll側Compose Bitmap overlayを使い、GPU Surfaceは背面で維持する。GPU画素処理/presenterファイルは変更しない。保存像、撮影時案内像、RAW基準案内像を別ラベルで表示し、編集未反映を明示する。比較/編集/保存の有効化は従来exact gateのまま。

exact presentedの同request値を観測したらoverlayを不可逆に外す。選択/再試行/背景化/trimは旧handoffを閉じ、post失敗・取消・late結果の未公開Bitmapはrecycleする。公開済BitmapはCompose/RenderThreadが参照し得るので明示recycleせず参照を落とす。cacheは独立copyのみをrecycleし、trim/retryはcache世代を更新して遅い書込を拒否する。UIへ渡した結果を再cache化しない。

## 検証の区別

- 旧契約の `ffe3b5a6`：lint/unit/APK build成功、Android合成10件中9成功/1失敗。SOS後entropy2bytes＋EOIをImageDecoderが受理したため、完全拒否契約に失敗した。この履歴を消さず、新条件の合格には読み替えない。
- 新条件：直接sliceと構造/容量/向き、同snapshot half→FULL、原本不変・cleanup・取消・exact置換・late callback・cache寿命・saved lookup世代/TOCTOUを別試験する。
- 初回統合tree `28bcb734`：main/native/Kotlin/lint成功、unit1399件中fixture構築で1失敗・skip12。修正と再実行は別途記録する。最終合格ではない。
- half比較はlinear-ready、Bitmap作成、Compose draw記録（最終画面合成ではない）、exact native開始、exact GPU presentedを区別し、同入力/同条件のfallbackなし対照と比較する。pure GPU時間とwall timeを混同しない。
- GPU90/帯28、関連Android回帰、表示スクリーンショット比較と本人承認、最終CI/canonical/独立レビューが残る。実写真・Pixel・配布は別許可。

既存IssueとLiving Specを正本にし、重複する番号付きspec一式は生成していない。

## ローカル検証追記（61a678b1）

- build対象tree `e1bf4315`：lint・unit1399件（失敗0、skip12）・app/test APKの4task成功。初回unit fixture不整合は構築条件を修正し関連14件再成功。次buildでAndroid ownership testの型推論失敗を検出し明示型で修正。失敗ログは残す。
- 専用read-only AOSPでnative/JPEG/lookup/ownership/halfの25件成功。任意linear計測1件は初回引数不足skip、その後明示有効化して成功。UI ABBA/別capture1件も成功。旧entropy完全拒否失敗とは契約を区別する。
- 匿名1536×1024、Surface/preflight準備後のABBA（FULLのみ／half→FULL／half→FULL／FULLのみ）：half線形ready421〜502 ms、Bitmap-ready585〜839 ms、Compose draw-record625〜885 ms、FULL開始586〜841 ms。対照FULL開始7〜21 ms。exact GPU presentedはhalfあり2633〜2901 ms、対照2026〜2745 ms。cold順序効果があり小標本、Pixel速度ではない。Compose記録は最終画面合成完了ではない。別visual runの待機時間は性能値から除外。
- halfによるexact開始遅延が約0.6〜0.8秒あり、採用判断は未完了。撮影画像や実機は未使用。
- 描画合成スクリーンショットで案内ラベル、Fit画像、無効操作→exactラベル/操作への置換を確認。背景復帰試験成功。ただし実際のeditor/provider選択全体をこのUI試験で覆ったとはしない。
- GPU ledger解放は成功。一方、GCを強制しないnative heap標本はFULLのみを含めrun毎約9.5 MB増加した。GC待ちか保持か未確定であり、反復メモリ安定・総確保上限の合格とはしない。原因確認を継続する。

### 追加メモリ診断（0d100731）

app/test APK build成功。新AVDで最初の試行はmain APK未導入によりinstrumentation開始前に失敗し、導入後の再試行は1件成功した。run復帰とfixture参照解放後、idleと診断専用GC要求を加えても、native heap標本はFULL/half交互6回で50.5→60.1→69.5→79.0→88.4→97.9 MBと増加した（decimal MB、約9,450,000 bytes/run）。GC待ちのみで説明できたとはしない。1536×1024×3×2=9,437,184 bytesとの近似は相関であり、RGB16漏れの証明ではない。

独立静的監査ではLibRaw mem-imageのcustom-deleter/free、row bufferとchannel解放を確認したが、原因は未確定。FULL decodeのみとGPU表示のみを別instrumentationで切り分ける。GPU90/28は原因確認前には進めない。AVDは終了後停止しQEMU0/adb空を確認した。製品にGC追加やメモリgate緩和はしていない。

### 経路分離と製品E2E（8d5f39df）

全4task成功。decode-only / GPU-only は別instrumentation・開始前process無しを確認し、実行中PIDを記録して各4反復。FULL decode-onlyのGC要求後native heapは5,645,872→5,645,824→5,646,032→5,646,160 bytesで平坦。GPU-onlyはRAW decodeなし、64×48 ImageReader、shared linear inputの4新ownerで39,914,400→49,368,592→58,818,688→68,265,040 bytesと増えた。小Surfaceでも再現し、RAW RGB16の解放漏れとは本試験から言えない。

既存main相当D1 `a467b0ef` / tree `3e8c7871` のAPKを保存済provenanceとSHA照合して比較した（main `f2c94cc0` と同tree）。同じGPU-only試験・別processで39,901,072→50,422,304→59,868,560→69,314,912 bytesと累積した。初回表示差分だけの回帰ではなく、既存GPU owner/context側を含む未解決問題。GL ledger 0はdriver/allocator全量解放の証明ではない。次は同一ownerでphotoのみ4回入替える限定診断を行う。製品GPU実装は変更していない。

製品E2E4件は各method新processで成功。専用Contextのcache/noBackup＋private wrapped ContentProviderを使い、実base-file lookup→saved/cache→recipe/source/JPEG/sidecar不一致のmiss→embedded/half、原本/JPEG/journal不変を検証した。actual renderer callback→main handoff→Compose案内→exact GPU presented→案内除去、late拒否・背景復帰も成功。Reader mockによる試験とは区別する。端末共有MediaStore書込・実写真使用なし。

### 同一GPU owner内のphoto反復（13309e1aの試験）

same-owner診断はbaseline APKと候補APKを別processで各1件成功。各4回のpresentをdrawCount増分で確認し、closePhoto後ledger0、最後owner/thread解放を確認した。GC要求後native heapの生bytesはbaseline 42,903,552→43,150,000→44,244,944→44,416,256、owner終了後40,977,392。候補は23,808,992→24,039,808→24,000,224→24,171,664、owner終了後21,047,632。同AVD内でbaseline→候補の順なので絶対値差を性能改善と解釈しない。

新owner毎の約9.45 MB増を同owner4photoでは再現せず、owner/context生成側へ限定できた。ただしdriver原因の断定や4反復による完全安定証明はしない。初回案内固有のnative/decode増とは分け、既存GPU全体の未解決事項として残す。製品のEGL/driver/GC動作は変更していない。

### 公開前の回帰結果

- `13309e1a` の全4task（lint/unit1399件・失敗0・skip12/app/test APK）は成功。`e00517d8` は文書のみ差分。
- `e00517d8` clean provenanceでGPU90case採取・band28比較が成功。採用baseline `51b691ad` との全90case RGB8全画素が完全一致した。環境差ありの既存exact policyでpass、baseline更新なし。
- 新規Android試験は35 method成功（複数の固定snapshotで段階的に実走、後段変更は試験/文書のみ）。旧entropy完全拒否の9pass/1fail、任意計測の初回skip、fixture型推論/構築失敗とは区別する。
- 既存関連回帰22件成功：Original24GpuInput11、HalfPreviewNative2、admission2、files1、12MP実UI lifecycle1、surface1、concurrent2、product入力2。Concurrentは入口引数gpuExportBoundaries/gpuSaveSafetyの不足で2回失敗し、元ログを保持して正しい引数で再成功。製品処理前のharness失敗でありassert/gateは変更していない。
- 各固定headとsource/APK hash・匿名成果・プロセス境界をローカル証拠に保持。boot安定/前後system_server一致/ANR・crashなし、終了後QEMU0/adb空を確認。ローカル生ログは公開しない。
- 24MP opt-in、Pixel/実写真、host native CTestは未実施。実機品質・速度やメモリ完全安定の合格にはしない。
- half採否と画面本人承認、最終remote CI/canonical reviewが未完了のためDraftを維持する。
- GPU owner/contextの限定既存観測は [#276](https://github.com/reitojike/cinematic-camera/issues/276#issuecomment-6895266039) に本人の具体的承認後に記録した。最初の投稿拒否を別経路で迂回せず、新しい承認を得て同じ内容を再試行した。
