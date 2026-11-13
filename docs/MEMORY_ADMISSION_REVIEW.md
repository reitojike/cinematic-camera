# GPU入力のメモリ拒否表示と固定条件の見直し（#260 partial）

## 今回の修正と事実の境界

通常GPUで、型付き `Original24MemoryUnavailable` が汎用現像エラーへ落ちる問題を修正する。ActivityManagerの拒否観測がある場合は「メモリ不足のため現像を開始・継続できません（空きメモリが受付条件未満、またはOSの低メモリ状態）」、割当失敗など観測なしの場合は「現像用のメモリを確保できませんでした」と表示し、原本不変と時間をおいた明示再試行を案内する。未知例外や任意のcause/messageをメモリと推測しない。GPU texture予算のエラーは別分類のまま。

拒否時だけ、既存 `CinematicRaw` に `ORIGINAL_MEMORY_REJECTED`、段階enum、固定例外分類、同じActivityManager.MemoryInfoのavailMem/lowMemory/threshold、現在のrequiredAvailableBytes、既知のoutputPixels（未判明はunknown）を記録する。成功時の連続採取や写真名/URI/原本識別/例外messageは記録しない。数値は今回の拒否判定に使った値であり、/proc推定やアプリPSSではない。端末ログはローカル扱いとし、写真別値と結び付けて公開しない。

本変更はコード上の例外分類漏れへの対処であり、実機で発生した個別事象の原因確定や実機動作確認を意味しない。

受付、割当、frame/input保持、dispose、明示retry、保存owner/journal/公開不明HOLDは変更しない。入力JNI境界では厳密なDNG_MEMORYだけを型付きメモリ失敗へ変換する。一般のIOException messageやwrapped causeから推測しない。保存処理で型付き例外が別の失敗契約へ変換される箇所は今回の画面分類へ無理に統合しない。保存の受付失敗も共通拒否点の診断対象だが、全保存エラー表示の改善完了とはしない。#260を閉じない。

## OS回収と事前gate

Androidはキャッシュを保持し、kswapdによる回収やzRAM、必要に応じたプロセス終了を管理する。空きRAMが少ないだけで故障とは限らず、利用者に毎回他アプリ終了を要求する設計を目標にしない。一方、割当成功や処理中の余裕をOSが保証するわけでもない。[Android memory allocation](https://developer.android.com/topic/performance/memory-management)

`ActivityManager.MemoryInfo.availMem` はシステムの利用可能メモリの指標で、確保可能量の保証ではない。lowMemory/thresholdもアプリheap上限やPSSとは別である。[MemoryInfo](https://developer.android.com/reference/android/app/ActivityManager.MemoryInfo)

`/proc/meminfo` のMemAvailableは、ファイルLRU・回収可能slab・watermark等を考慮した推定である。API値と同一と仮定せず、対象OSの実API値を同時点で採取する。AOSPの一実装や別時刻の値からPixelの値を再構成しない。[Linux proc](https://docs.kernel.org/filesystems/proc.html)

現行gateは原寸decode/saveでlowMemory=falseかつavailMem>=768MiBを割当前/途中で要求する。このためOS回収によって実際には処理できる状況も事前拒否する可能性はある。逆にこの固定値を通っただけでnative/GPUの割当を保証できない。今回は閾値を下げず、実測で過剰拒否と安全余裕を区別する。

## 自アプリで既に解放するものと次の最小案

- `GpuPhotoRollInputs.open` は前ownerのclose確認後のみ次入力を許し、入力結果はsnapshot/FD/native所有とtokenの解放後に返す。close未確認のownerは保持する。ここを強制解放しない。
- `Original24PreviewSession` はfull decode後のRGB16を縮小してtransient資源を閉じ、GPU用は1024制約のAS_SHOT入力だけ返す。
- `GpuPhotoRollEditor` は背景/trim/disposeで表示sessionを閉じ、保存開始時もpreview終了とretired owner解放を待つ。保存不明状態のownerを「不要キャッシュ」と扱わない。
- `PhotoRollPreviewCache` は表示用pair最大8MiB、明示retryや別keyでclear、Activityのlow-memory/trimにも既存解放がある。通常GPUの主なnative decode量をこのcacheだけで説明できない。
- 次の限定調査は、失敗直前の自アプリ内で生きているpreview/retired owner/cache/保存ownerを匿名数で対応付け、解放済み資源を二重計上しないこと。未使用cacheだけの解放で回復するなら、その所有境界に限定して最適化する。GC強制・他アプリkill・公開不明owner破棄はしない。[Manage app memory](https://developer.android.com/topic/performance/memory/manage-app-memory)

## 12MP / 24MPの必要量モデル（計算例、実測peakではない）

小数MBではなくMiB換算。単一Bayer16 rawと同寸法の処理面を仮定した説明用の項目で、raw格納/active/cropが異なる素材にはそのまま適用しない。LibRawのcodec/demosaic/作業面・allocator・driverの全量は含まれない。

| 項目 | 12,000,000画素 | 24,000,000画素 | 同時所有と根拠 |
|---|---:|---:|---|
| encoded DNG snapshot | 最大64MiB | 最大64MiB | native `dng_decoder.cpp` のencoded vector。ディスクsnapshotとは別に存在 |
| Bayer16 raw例（2 byte/画素） | 22.9MiB | 45.8MiB | LibRaw unpackの代表payload。形式/格納寸法依存、全量上限ではない |
| LibRaw4 channel ushort処理面例（8 byte/画素） | 91.6MiB | 183.1MiB | demosaic等の追加scratchを含めない |
| dcraw_make_mem_image RGB16（6 byte/画素） | 68.7MiB | 137.3MiB | コードがRGB16/3chを検査。make時にはLibRawとencodedがまだ生存 |
| 原寸ARGB8888 Bitmap（4 byte/画素） | 45.8MiB | 91.6MiB | 保存側、native decode後の段階。decode最大と無条件加算しない |
| GPU表示入力 Float RGB（最大1024×768×12） | 最大9MiB | 最大9MiB | 原本画素数と同じ全寸法Float配列ではない |

`dng_decoder.cpp` はmake_mem_image後にLibRaw recycleとencoded解放、その後RGB16 raster書出し・processed image解放。snapshot/RGB16ディスク容量を丸ごとRAMへ加算せず、page cacheは別観測する。保存は `Original24Attempt` のdecode/close後にanalysisとGPU帯出力へ進む。帯texture予算は原寸Bitmap/native/driverを全て含む予算ではない。

単一画像の項目を足した概算を即受付値にしない。12MP/24MPでraw格納寸法・圧縮・native段階・保存のBitmap/帯texture・A保存+B入力の同時所有が違うため、必要量は段階と所有集合ごとに求める。現行512MiB LibRaw raw制限もプロセス全体上限ではない。

## 既存証拠と改善候補

#264 cold/warm、#269 bounded-preview guard分離、#360 GPU保存A帯保持+B入力の合成12/24MPを再利用する。#360の実測は旧head、native unpack/process別とA/B native decode重複は未測定、外側受付一部は合成条件。各列は別時刻最大で加算不可、Pixel必要量や低メモリ安全性に読み替えない。

推奨順は次のとおり。

1. 拒否時の実ActivityManager値と段階を取る（今回の診断）。本事象が内側768MiBか、OS lowMemoryか、別受付/例外かを識別する。
2. 既存合成probeへnative unpack/process/make/recycleの同時所有観測を最小追加する案を先にレビューする。raw格納寸法/圧縮と12MP/24MP、単独/保存並行、解放前後を区別する。実機の確認は別許可。
3. 実測とコード上限から、OS lowMemory・threshold余裕、自アプリmanaged/native/GPUの追加必要量、実行中ownerの残余予算を組み合わせる段階別候補を作る。単純な「768→512」や12MP一律半減をしない。guard通過後の割当失敗・取消・close保持は引き続き必要。
4. 不要な自アプリ所有が見つかれば先に解消。gateの限定変更は境界/未知資源/圧迫時の停止、原本不変・別写真混入なし・公開HOLDを回帰し、測定根拠とともに別差分で提示する。

| 候補 | 安全性と採否 |
|---|---|
| 自アプリの未使用cache/終了済みownerの回収 | 優先調査。表示中画像・保存owner・未確認closeは対象外。既存解放で足りる場合は追加しない |
| 明示retry | 現行維持。毎回新しい資源観測を行う。失敗後の自動ループ/固定sleep/guard再試行緩和は採用しない |
| 表示用の縮小decode | full decode前のピーク削減候補だが、LibRaw half-sizeや埋込像は現行と画角/CFA/色/画素が変わり得る。#233/#241と分離し、本人判断・比較前に黙って代替しない |
| GPU帯/出力分割 | #286の帯処理は既存。native full decodeと原寸Bitmapは残る。ストリーミングJPEG等は別の出力品質/公開契約評価が必要で今回は実装しない |
| OS任せで固定gate全撤廃 | 採用しない。OS回収は有効だが全段階の必要量・低メモリ失敗回収をまだ実証していない |

今回の表示改善は#260の完了ではない。新release/実機操作/実写真利用/新依存・強制GC/上限緩和は行わず、Draftで親への提示を待つ。


## 最優先レーン1の段階とPixel計測計画（未実施・別許可待ち）

第1項の限定差分が本PR。ログの `requiredAvailableBytes` は現行の受付要求であり実測必要量ではない。`estimatedAdditionalBytes=unknown` は全working setの見積りが未確定という意味。既知画素数からの `rgb16PayloadBytes=6×outputPixels` は一面の計算値であり必要量を代用しない。GPU入力はpreflight済みgeometryから画素数を渡し、未解析の保存境界等ではunknownを維持する。写真別値を公開しない。

Pixelの計測は、次の段取りを提示してから別途本人の操作/読取許可を得る。今回実行しない。最初の枠は45～60分を目安とし、長い処理は途中報告、時間を理由にownerを強制終了しない。

| 時間目安 | 操作 | 採取と制限 |
|---|---|---|
| 5～10分 | 対象Pixel/候補版/署名、本人指定12MP・24MP各1枚、空き容量・熱・既存接続を確認 | 写真/名前/URIはローカルのみ。新配布/更新/USB設定変更は別許可。意図的RAM圧迫や他アプリkillなし |
| 10～15分 | 12MPを単独で開く→編集/比較→同写真再開→原寸保存→終了 | 開く前、preflight、decode native、縮小/入力解放、GPU preview保持、save decode、Bitmap/帯保持、公開後、close後。冷間は新processを確認できる場合だけそう呼ぶ |
| 15～20分 | 24MPを同順で単独確認 | 同じ観測条件で寸法・圧縮差とcold/warmを分ける。片方の成功を他方へ転用しない |
| 10～15分・別枠可 | 単独成功後に12MP A保存+Bオープンを1回 | #360の同時所有契約を参考に、実際のowner重複区間だけ集計。OS圧迫、取消、最悪同時所有を一括で追加しない |

観測は実ActivityManagerのavailMem/lowMemory/threshold/totalMem、managed heap、native heap、process PSS/RSSと利用可能ならmemtrack GPU/graphics、texture台帳の現在/最大bytes、時刻・段階・owner数を対応付ける。Mali等のGPUはシステムRAMを共有するため、texture台帳をPSS/RSSへ足して総量としない。memtrack/driver分類が不足する場合は未測定と明記する。OS側のavailMem変化には他process/回収も含まれ、アプリ使用量差分とは断定しない。

通常releaseで取得できないnative細分段階・texture台帳は今回の拒否ログだけでは測れない。既存metrics/observerの最小opt-in接続を別差分でレビューしてから測定する。emulator専用instrumentationのgateを外して本人素材へ流用しない。外部標本は0.5～1秒程度を候補とし、実測負荷を記録する。短いnativeピークを取り逃がすため採取最大と真peakを区別し、解放後のallocator保持や共有ページの帰属も未確定とする。各指標の別時刻最大は加算しない。

第3項は、この段階別観測後に画素数/RAW格納/圧縮と同時owner数に応じた追加必要量＋余裕を提案する。OS回収可能なcacheをゼロ扱いしないが、MemAvailableやcached processの全量を安全余裕にそのまま足さない。`lowMemory` のみで停止する案も比較対象とするが、lowMemory=falseでも巨大native割当が失敗し得るため無条件採用しない。現在の768MiBは測定前に撤廃しない。

## 割当失敗とOS終了、#365との境界

managed OutOfMemoryErrorは既存previewの限定catchに加え、GPU入力worker結果と入力UIの境界でも型付きメモリ失敗へ変換する。decode前後の割当失敗を対象とし、その他のErrorは元の分類を維持する。LibRaw不足メモリ/native bad_allocには既存の固定DNG_MEMORY等の失敗経路がある。ただし全native/driver失敗・プロセス終了をKotlin例外で捕捉できる保証はなく、OOMを常に安全に回復できると約束しない。今回、例外やログから再割当ループを作らない。

OSがprocessを終了するとfinally/close callbackが実行される保証はない。再起動後は既存journal/公開照合による復旧であり、#365の保存世代とpublication不明HOLDを守る。「メモリ不足だったから未公開」と推定して新世代をinsertしたり、残ったowner/原本を削除しない。#365との連携点は、終了前に確認済みの取消/公開証拠と、起動時の同一generation照合である。今回その状態機械・journal形式・復旧処理は編集しない。
