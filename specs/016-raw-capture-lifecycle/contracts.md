# 内部契約: 保存・現像・公開

## 形式別の保全

| 入力 | 保全する原本 | 内部JPEG | 共有公開 |
|---|---|---|---|
| RAW+JPEG | 引渡し確認後は共有DNGの1つ | 受領した撮影JPEG | DNGを原本保存、成功した現像JPEGを後から別保存 |
| RAWのみ | 引渡し確認後は共有DNGの1つ | なし。欠落を失敗としない | DNGと、現像に成功したJPEG |
| JPEG単独 | 既存経路のJPEG | この契約の仮JPEGにはしない | 現行のJPEG保存を維持 |
| RAW+JPEGの部分成功 | 完了した形式だけ | 完了していれば保全 | DNG成功時のみ原本保存可。JPEGだけなら自動RAW現像は不可 |

受領成功はCameraXの形式別callbackとファイルclose/検査で記録する。保存成功とRAW decode有効性は別。
古いcallbackは撮影UI状態を変更しないが、その撮影記録に所有されるファイルを削除する理由にはしない。

## DNG原本の引渡しと一時コピーの解放

公開操作の`PUBLISHED`と、唯一の永続原本にできる`SHARED_READY`を分ける。
撮影用一時DNG、共有pending DNGが一時的に共存することは許容するが、通常完了後の恒久ミラーを作らない。

| 引渡し状態 | 進める条件 | 保全/再開 |
|---|---|---|
| `LOCAL_RECEIVED` | 形式別callback、close、非emptyとサイズを台帳へ記録 | ここで撮影busyを解除可能。hash/readbackはまだ待たない。decode有効性は別判定 |
| `SOURCE_VERIFIED` | 撮影優先の後段IOで入力全バイトSHA-256を計算・台帳commitし、公開操作を準備 | hash未完/失敗はLOCAL_RECEIVEDのまま保全。重い検証を撮影busyに含めない |
| `SHARED_VERIFYING` | コピー先close後のreadbackで入力サイズと全バイトSHA-256一致、公開照合成功 | mismatch/読取失敗/公開不明は削除せず停止。公開API成功だけで進めない |
| `SHARED_READY` | pending=0、同一URIの読取可と内容一致、共有URI/hash/状態を台帳へcommit | 未commitなら一時DNGを削除しない。復旧で共有先を再確認 |
| `LOCAL_RELEASE_PENDING` | 一時DNGのwriter/reader leaseが0、同じ共有原本が読取可/一致と再確認後、状態を先にcommit | 同じ撮影の一時DNGだけを削除。削除失敗は後処理待ち、再公開しない |
| `SHARED_ONLY` | 一時DNGの不在を確認してcommit | 以後は共有原本URIだけを参照。内部原本を再作成しない |

削除と台帳commit間でdeathした場合は、一時ファイルの有無と共有原本を照合して解放を完了する。
共有先が不明/変更済みなら残存一時DNGを保全し、自動削除しない。一時ファイルが既になく共有先も失われたら
原本不在として停止し、保全成功と表示しない。仮JPEG、別撮影、共有DNGを一時DNG cleanupへ渡さない。
撮影busy解除は受領記録とpreview復帰を基準にし、readback/hash/共有commit/解放は待たせない。
共有原本が確立するまで自動現像を開始しない。解放処理は撮影優先で遅延可能だが、失敗を隠して二重保存を常態化させない。
read leaseの生countは永続化せず、現在processのsessionとattemptごとに開いている参照から管理する。
process death後の再起動では旧processの所有attemptを無効化し、現在の参照から再構築してから解放を判定する。
同じprocessでcancel/世代変更しただけではleaseを消さず、実際のreader/writer解放を待つ。

アプリのread leaseは外部アプリの変更/削除を防がない。最終照合と削除を跨ぐ外部操作に原子的保証はなく、
共有原本の消失まで保護するバックアップではない。恒久二重保存を避ける合意の限界として扱う。
原本照合とJPEG公開も一括transactionではなく、最終照合後の外部操作について検知前公開を排除できない。

## 原本参照の再取得と外部変更

- `SourceRef`はURI/サイズ/SHA-256/参照権の由来を持ち、raw pathやファイル名だけで同一性を決めない。
  共有原本はread-only FD/streamで開き、処理開始・JPEG公開前に照合する。途中のreadエラーやhash不一致は公開しない。
- 自分の新規MediaStore原本を開く場合と、再インストール後や本人選択で開く場合の権限を分ける。
  旧インストールの所有やpersistable grantが残るとは仮定しない。本人選択のread grantだけを使い、必要ならpersistする。
- 開始/公開直前の検証で`SOURCE_UNAVAILABLE`（削除/移動/権限失効/volume不在）と`SOURCE_CHANGED`（同じURIでも別内容）を検知したら現像・公開を止める。
  見つからないことを削除と断定せず、再選択・アクセス再取得を案内する。同名の別DNGへ自動でrecipeを適用しない。
- 再選択したURIが同じhashなら既存台帳のrecipeと再関連付けできる。hashが異なる場合は別原本として本人に示す。
  再インストール等で台帳/recipeそのものがなければ新規編集となり、過去設定・仮JPEG・出力との対応を復元済みとは扱わない。
- DNGの公開とJPEGの公開は別の画像。ギャラリーやPhoto PickerのRAW/JPEGグループ表示を保証しない。
  DNGを選べないときは標準ファイル選択へ切り替え、取得した実データのDNG対応を検証する。写真の外部送信は行わない。

## #78との受け渡し（実rendererは未登録）

- 入力: `captureId`、`renderGeneration`、`attemptId`、共有原本の読み取り専用URI/FDと内容識別、固定recipe、独立した派生出力先、cancel確認口。
- recipe: schema/renderer/baselineの識別版、`stage-clear-v1`、明示strength（初期0.5）、外部決定の露出定義、
  顔入力の有無/定義、出力の寸法/向き/色空間/transfer/JPEG設定。#79未接続の顔/露出を推測で埋めない。
- 出力: 成功時だけ完全なJPEG参照、検査可能な寸法/向き/色定義と処理識別。失敗・未対応・中断は区別する。
- rendererは原本/仮JPEG/MediaStore/撮影状態/台帳を変更しない。保存側は画素処理順やgoldenを変更しない。
- 読み取り参照は処理中保持し、アプリ側は削除・上書きしない。外部変更を排他できるとは扱わない。cancel要求と解放完了は別。
- レシピ変更は新generation。旧attemptの結果は新generationへ採用せず、その未公開の派生出力だけを片付ける。
- 既定値の将来変更で既存要求を変えない。同一環境・同一版の再生成を検証し、異なるCPU/GPU間のbit一致を先に約束しない。

### 実行制御の内部型と所有

`AutoDevelopRequest`はrender前に原本参照/recipe snapshot/schema/renderer契約、generation、原本の通常向きでの全寸法、
品質区分、顔/露出/profileの対応状態を固定する。`RendererAdapter`は純粋な対応可否判定と、IOを開かないattempt生成だけを行う。
`AutoRenderAttempt.render`の中だけで、供給されたread-only stream/FDと固有attemptのprivate出力を開ける。
recipe payloadの意味は実renderer adapterの責務であり、制御側は未知schemaや未確定の顔/露出/profileを推測しない。
decode/入力準備中に判明した未対応は`AutoRenderUnsupported`としてHELDへ、通常失敗はRETRYABLE、中断はINTERRUPTEDとして分ける。
現在の`PhotoRollRenderer.Preview`は異なる型であり、512px試験表示を成功receiptへ変換して登録しない。
`AutoRendererCatalog`のproduction登録は0、撮影/lifecycle/資源計測からの自動投入も未接続である。

停止要求と撮影予約は`AutoDevelopCoordinator`の短いlockでlatched状態を更新し、描画/IO/closeを待たない。
rendererへの明示停止口（native CancellationSignal等）は別のcancel executorから呼ぶ。通知自体が戻らない間も
attemptとslotを保持する。通知をcloseと扱わず、native処理と提供FDの寿命を維持し、強制解放しない。
停止intentはprocess内のrunner/slotを組で固定し、遅延した旧停止で次のnative attemptを取り直してcancelしない。
停止/失敗/検査成功の完了判断はattempt IDに固定し、台帳commit確認まで保持する。物理slotの解放や新slotの取得で
その判断を成功へ再評価しない。process death後に判断が失われたRENDERINGは既存の中断復旧に従う。
実kernelの中断点や即時停止は保証しない。renderer.closeが成功してから提供IOを解放し、出力の検査はその後に行う。
出力欠落/失敗latchはINVALID_OUTPUTで停止するが、実資源が閉じていれば別jobのslotを塞がない。

`AutoDevelopQueue`の全submit/復旧/世代変更/公開は1つのIO executorで直列化する。
同じ撮影の新世代のsubmitは先に旧attemptへ停止を要求する。metadataを受理しても、実行は旧ownerの資源解放まで開始しない。
同じ要求の再配送や別撮影の投入だけで、現在の正常な処理を中断しない。
旧世代の未解放ownerはqueueから除去せず、最新世代だけに新規作業を認める。retryはRETRYABLEに対する明示操作だけで、
即時無限retryを行わない。RENDERED/PUBLISHINGは同じ成功attemptと公開操作を再開し、再renderやartifact差替えをしない。
process復旧時のRENDERINGは未確認出力のままRETRYABLEへ戻し、存在/byte数だけで成功へ昇格しない。
復旧対象一覧は全件検証した後だけqueueへ受理する。新世代の破損/未知版で復旧失敗した際に、部分的に読めた旧世代だけを実行しない。
起動時の完全な台帳列挙とproduction条件の観測接続は後続gateであり、省略した一覧で既存jobを再開しない。

manual factoryとauto backendは同じ`captureId:RENDERED:generation`について共通のprocess所有権claimを使う。
別ownerの同じ台帳/公開操作への接続は、資源のclose後も拒否する。取消やActivity再生成で別AtomicFile writerを作らない。
manual UIが既存auto出力を扱う統合では既存ownerへ接続し、独立した編集なら別の安定したlogical request IDを使う。
role名を変えるだけで既存operationや成功artifactを上書きしない。このallocator/表示統合は後続の接続gateである。

## 1公開操作の状態と照合

DNG原本保存と現像JPEG公開はそれぞれ独立した操作IDを持ち、1操作につき1行。
不変のDNGは`captureId + ORIGINAL`、現像JPEGは`captureId + RENDERED + renderGeneration`で識別する。
recipeの変更でDNGの公開操作を増やさない。操作ID、ランダムtokenを含む期待名/volume/path/MIME、
入力サイズ/検査情報を**insert前**に台帳へcommitする。通常の再試行では同じ操作IDを使う。

| 台帳状態 | 動作 | 復旧/失敗時 |
|---|---|---|
| `PREPARED` | まだinsert未発行。`INSERTING`を台帳commitしてから1回だけinsert | `PREPARED`への復旧では未発行が確定。commit失敗ならAPIを呼ばない |
| `INSERTING` | insert結果のURIを台帳へ記録 | 応答前のdeathは期待名等で照合。候補なし/複数/照合不能は`UNKNOWN`。自動再insertしない |
| `ROW_RECORDED` | 同じpending URIと入力を照合し、`COPYING`を先にcommit | 完成した内部sourceを保持。commit失敗なら書込みを開始しない |
| `COPYING` | bounded copy、全stream close、同URIの全byte readback | 完成内容一致なら再コピー不要。不一致とclose成功を確認できた未公開の自分の行だけ再コピー。読取不能/close不明は停止 |
| `BYTES_COMPLETE` | 検査結果をcommit後、`PUBLISHING`を先にcommitしてからpendingを解除 | commit失敗なら公開APIを呼ばない |
| `PUBLISHING` | 当該URIの状態/所有/サイズ/内容識別を確認 | pending=0で一致なら`PUBLISHED`、pending=1で完成内容一致なら同じ行のpending解除を再試行、その他は`UNKNOWN` |
| `PUBLISHED` | 同URIの公開状態/内容を再確認し、`SHARED_READY`をcommit | 行が後で消えても自動再作成しない。公開済み行をrollback削除しない |
| `UNKNOWN` | 自動操作を停止し、保全と要確認を示す | 元データを保持し、照合できるまで新規insert/削除/上書きをしない |

実装の版2は既存の状態コード0〜3を保ち、`COPYING`以降と引渡し状態を追加する。版1は旧4状態だけを受理し、
読取りでtoken/URIを書換えない。`PUBLISHED`以後の共有先不在/変更は既存状態を保持して例外停止し、再insertしない。
`SHARED_ONLY`は過去の引渡し完了記録であり、現在も共有原本が存在するという照会結果ではない。

`RawCaptureStore.handoffSource(journal)`で同じ公開台帳を先にbindし、1 process・1撮影につきstore/journal/coordinatorを各1つ所有する。
通常の`openOriginalReader`と`verifyOriginal`を含む全内部DNG readerをleaseで数え、close成功まで減らさない。
解放予約中と永続`LOCAL_RELEASE_PENDING`以後は通常readerを新規openせず、予約所有者だけが検証用readと固定DNG削除を行える。
未解放streamがある場合、coordinatorはhandleを保持してclose再試行だけを許可し、追加IO/公開/削除へ進めない。
process再生成時も台帳をbindしてからreaderを公開する。複数instanceを作って排他を迂回する使い方は契約外である。

期待名だけで行の所有を証明しない。volume/path/MIME/アプリ所有/token/完成内容を照合し、
外部の行や他撮影を巻き込まない。URI記録前のinsert成功とdeathをまたぐ窓も故障注入で確認する。
復旧ではすべての非terminal状態で、書込み前にpending/所有を再照合する。未完成の自分のpending行だけが
同じsourceからの再コピー対象。完成内容の照合は公開採用の条件であり、途中の行を完成扱いにしない。
`INSERTING`で1件の自分のpending候補を確認できればURIを記録して`ROW_RECORDED`へ進める。
照合APIが必要な情報を得られない場合、広範囲権限を追加せず`UNKNOWN`へ止める。
公開済みかもしれない行が見つからない場合も、自動で新しい行を作って補わない。
ユーザーが再出力を選ぶ導線は後続編集/出力の責務で、内部の無限retryにしない。

DBとMediaStoreを跨ぐexactly-once保証は主張しない。不明時に自動停止することで、重複公開を避ける。
失敗した自分の未公開pending行をcleanupする場合も、所有とpendingを再確認し、台帳へ結果を残す。
共有原本・仮JPEGはこのcleanupへ渡さない。一時DNGの解放は上記の引渡し契約だけで行う。

## 手動入口とUI接続API

`AndroidAutoDevelopQueue.prepareManual(context, source, JpegGenerationRecipe)`は固定metadataのcommit/readback後に
`CompletableFuture<ManualDevelopHandle>`を返す。UIでcaptureId/generationを作らず、grantを昇格させない。
handleのrequest/qualityは読み取り専用。start(observations)→status()でRENDEREDを確認し、実寸法/縮小暫定品質を表示する。
ユーザーの保存操作でpublish(observations)を呼ぶ。PUBLISHED後のOpen直前だけconfirmPublished(observations, shouldStop)を呼び、
そのfresh結果URIを使う。statusの履歴からURIを開かず、別AndroidRenderedJpegOperationや私有JPEGのコピーを作らない。

取消はhandle.requestStop()、RETRYABLEまたは未着手SOURCE_UNAVAILABLEの再選択後は明示retry()。
source変更は別source identityとなり、失効したgrantを永続権限へ書換えない。UNKNOWN公開や改変をretryでリセットしない。
prepare/start/status/publish/retryのfutureを待つ間にUIのpreview枠を持たない。

previewは同じDevelopExecutionへfresh common観測をupdateConditionsし、job別rendererAvailableで
tryStartObservedする。shouldStop(token)をstage境界で確認し、実native/stream close後だけDevelopExecution.completeAfterResourcesClosedする。解放時は待機queueをwakeする。
取消/例外/Activity再生成でclose不明ownerを捨てない。現在このPRはphotorollを変更せず、UI側接続・実観測は別PRで検証する。

取消は永続edit generationと別のprocess内epochを失効させる。queued start/publish/retry/confirmは呼出時epochを固定し、
後着IOは失効済みepochで台帳を進めない。公開権威もepochに固定し、new startだけで古いSaveを復活させない。
PUBLISHINGの中断後も、新たな明示publishが同じ公開ownerを再開する。fresh確認は完了まで同epochを照合する。
