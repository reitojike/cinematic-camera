# Implementation Plan: RAW撮影後の保全と自動現像

**Branch**: `docs/030-raw-save-auto-develop` | **Date**: 2026-11-04 | **Spec**: [spec.md](spec.md)

## Summary

撮影保存、原本保全、現像、共有公開を分離する。最初のPRはこの設計と整合する方針更新のみ。
撮影へつながない純粋policy、永続化、公開、撮影接続、#78統合を順に別の小さいDraft PRとする。

## Technical Context

- 単一Android app、既存Kotlin/CameraX 1.6.2/標準MediaStoreを維持。新依存は今回追加しない。
- Pixel 9a/API 29以上を共有保存の先行対象とし、API 28の現行非対応表示を広げない。
- 一時DNG・仮JPEG・台帳は`noBackupFilesDir/raw-captures/<captureId>/`へ固定し、撮影ごとの小さい台帳を`AtomicFile`で更新する。
  共有への引渡し確認後のDNGはここに恒久保存せず、共有URIの原本1つを参照する。
- 台帳・ファイル・MediaStoreを横断する原子transactionはない。単一writerと照合による復旧が必要。
- テストは既存JUnit、fault injection、最終headのAndroid CI。実画像の非公開検証は合成テストと分ける。
- 実測前はcamera sessionを使用中のdecodeを許可しない。撮影待ち時間の数値や即時cancelを保証しない。

## Constitution Check

- I/III: 合意と観測可能な仕様、未実装/未検証を区別。実機結果を生成しない。
- II/V: 標準API、単一app、依存なし。CameraDevice/sessionへ独自介入しない。
- IV/VI: DNG共有保存・原本不変・private仮JPEG。今回のJPEG公開方針へ原則IVと002計画を明示改訂する。
- 初回/設計後のgate: 共有原本や既存写真を削除せず、一時DNGだけを検証済み引渡し後に解放する。
  参照許可は本人が選んだURIへ限定し、新しい広範囲権限は導入しない。公開/CI/レビューは別gate。

## 現行との差と所有境界

従来の`CameraProbe.captureFiles`はcache stagingでRAW/JPEG callbackを待ち、両方を公開してcleanupしていた。
RAW/RAW+JPEGは下記の永続stream保全へ切り替える。JPEG単独は既存`PhotoPublisher`の経路を維持する。
`PhotoPublisher`の公開済み行も含むrollbackとstaging cleanupを、RAWの復旧/一時解放へ流用しない。

| 担当 | 変更場所 | 今回触れない場所 |
|---|---|---|
| 保存設計/基盤 | `specs/016-raw-capture-lifecycle/`、将来`app/src/{main,test}/java/dev/cinematic/p0/rawcapture/` | `tools/raw_baseline/`、#78の比較fixture/画素処理 |
| #78移植 | `specs/015-stage-clear-android/`、`dev.cinematic.p0.raw`とPC比較の専用ファイル（PR #88） | 保存台帳・MediaStore・CameraProbeへの接続 |
| 後続の統合PR | CameraProbeから新保存経路へ1か所ずつ接続、必要なpolicy呼出し | 既存JPEG単独/動画/設定制御 |

これは提案境界。#78側の合意前に共有DTOや画素処理namespaceを作らない。
受け渡す意味は[内部契約](contracts.md)に固定し、共有型の所有者は統合時に1つへ決める。
調査判断とデータモデルは本planへ統合する（repoのSpec Kit運用と同じ）。外部APIは増やさず、内部契約だけを分離する。

## 保存・復旧の設計

1. 撮影IDと期待形式を台帳へ記録し、CameraX出力を専用永続領域へ渡す。cacheを唯一の保全場所にしない。
2. 形式別callbackを世代/IDで照合し、close済み成功ファイルを検査して受領記録する。
   大きさが正というだけでDNG decode有効とは扱わない。片方の成功は他方の失敗でも保持する。
3. 台帳の整合を確認して撮影busyを解除する。DNG共有保存や現像完了をbusy解除の前提にしない。
   DNG保全とpreview復帰の表示を、DNG共有保存成功・JPEG公開成功と区別する。
4. 共有DNGは元バイト列のコピーとする。close後の読戻しでサイズとSHA-256が入力と一致し、pending解除後も
   同一URIで読取り可と確認してから、共有URI/内容識別/引渡し完了を台帳へcommitする。公開APIの成功だけでは足りない。
   writer/readerが解放済みで共有原本を再確認できた後に、一時DNGだけを削除する。[引渡し契約](contracts.md)に従い、
   削除失敗やdeathは後処理待ちとして再開する。成功後に内部DNGをバックアップとして残し続けない。
   失敗/不明/検査不一致時は一時DNGと仮JPEGを保持する。仮JPEGはこの引渡しcleanupへ渡さない。
5. readyな原本参照を台帳から列挙し、1件ずつ処理する。画像バッファをqueueへ積まず、未処理件数が増えても
   既存原本を削除しない。容量不足時は新撮影を安全に失敗表示し、成功済み素材を保つ。
6. 現像は共有原本のread-only URI/FDから行い、恒久的なDNG再コピーを作らない。内部DNGがまだ引渡し待ちなら
   現像を保留し、共有原本の確立を先に行う。完全なJPEGを内部出力へ作り、decode/寸法/向き/色定義/byte数と
   原本同一性を確認してから公開へ渡す。中断・失敗で破棄できるのは派生途中だけ。仮JPEG/共有原本をcleanupしない。

### 台帳とprocess death

1撮影内の受領・現像・公開は別状態。schemaVersion、captureId、期待形式、形式別完了、相対参照、
renderGeneration、固定recipe、attemptId、出力検査結果、公開操作ID/行URI/状態を持つ。
原本引渡し状態と共有URI/サイズ/SHA-256、参照権の由来、外部変更/アクセス状態も保持する。
受領close/sizeのcommitと後段の入力hash完了を別状態にし、撮影busy解除で全バイトhashを待たせない。
read leaseの生countは永続化せずprocess-scopedとし、process session/attemptに結び付けて管理する。
process death後は旧processの所有attemptを無効化して現在の参照を再構築する。同一process内のcancelは
解放完了ではなく、実際のreader/writerが閉じるまで一時DNGを解放しない。
台帳の更新は単一writerで行う。AtomicFileは複数writerの排他やMediaStoreまでのtransactionを提供しない。
pathは専用root内に解決できる相対参照だけを受理し、壊れた/未知schemaは保全して要確認へ移す。

再起動時の`CAPTURING`は`INTERRUPTED`へ。成功callbackと台帳commit間のファイルは未確認として残し、
サイズだけで完了へ昇格させない。`RENDERING`はattemptを無効化して保留へ戻し、完成出力は検査記録と照合する。
`PUBLISHING`は[契約の照合手順](contracts.md)へ進む。背景化・process deathでも新しい自動撮影はしない。

### 共有原本の参照・権限と編集情報

- 原本はread-onlyで開く。MediaStore URI/内容識別を主にし、絶対パスやファイル名だけで関連付けない。
  providerの世代/metadataは変更検知の補助であり、内容一致はSHA-256で確認する。検証とコピー/ハッシュIOも撮影優先で後段へ回す。
- #78へはread-only stream/FDと原本識別を渡す。パス専用decoderしか使えない場合は、boundedな一時入力の
  必要性/解放/撮影影響を別判断し、恒久ミラーを暗黙追加しない。
- 共有原本の外部削除/移動/アクセス拒否/内容変化は`SOURCE_UNAVAILABLE`または`SOURCE_CHANGED`として区別し、
  現像・派生出力の公開を止める。監視通知だけを信用せず、処理開始時と公開直前にも原本を照合する。
  本人の再選択後も既知hashと一致するまで既存編集へ接続せず、別内容は新しい原本として扱う。
- 再インストールでは旧アプリのMediaStore所有扱い・保存URI・persistable grantを引き継ぐと仮定しない。
  後続編集では標準Photo Pickerの本人選択を入口にし、DNGが出ない/開けない場合はSAF `ACTION_OPEN_DOCUMENT`で
  ファイルを選び直す。返ったread grantを必要時persistし、失敗/失効時は再選択を求める。write grantや広範囲権限は要求しない。
- 内部台帳・仮JPEG・recipe・原本とJPEGの対応はアンインストール/データ消去で失われる。
  共有DNG/JPEGが残っても編集の続きや過去の強度/色温度を再現できるとは保証しない。再選択後は新しい編集として始める。
  sidecarや設定export/backupを無断導入せず、過去設定の復旧を約束するなら別の合意・設計が必要。
- 外部のギャラリーにはDNG/JPEGの両方が見え得る。二重表示の統合やDNG非表示をアプリから保証しない。
  Photo Picker/ギャラリーのDNG選択・thumbnail・表示、Filesからの到達性をPixelで後続確認する。
- 唯一の原本は外部から変更/削除可能であり、他アプリとの排他的transactionはない。
  原本照合と一時削除/JPEG公開も原子的ではない。最終照合後の外部操作による消失や、検知前のJPEG公開、
  共有前のアンインストールまで防げるとは主張しない。

### 受領台帳（T009・T011の台帳部分）

`CaptureJournal`の受領部分は撮影ID、revision、期待形式（DNGまたはDNG+JPEG）、形式別の受領byte数、
`CAPTURING`/`INTERRUPTED`/`FILES_RECEIVED`だけを保存する。これはcallbackとcloseを呼出側が確認したという
記録であり、ファイル存在やDNG decode・元バイト列のSHA-256・共有保存の成功を証明しない。
実ファイルの受領確認はT010、原本内容識別は下記の版2、共有原本への引渡しはT012以降、recipe/公開状態の拡張は後続の版で扱う。

台帳は4KiB上限の固定レイアウト、magic/明示schema版/台帳payloadのSHA-256で破損を検出する。
台帳digestはDNGのhashや改ざん防止署名ではない。未知版・余剰データ・不正field・別撮影IDは読取エラーとし、
上書き/自動初期化しない。版の拡張時は旧台帳の移行を別途検証し、未知fieldを読み捨てて書き戻さない。

内部pathは信頼できるapp-private rootから`<captureId>/receipt.bin`を組み立て、IDはASCII英数字/`_`/`-`の
1〜64字に限定する。root配下のcapture directory、base/`.new`/`.bak`のredirectを拒否する。
入力文字列の絶対pathを台帳へ保存しない。`AndroidRawCaptureQueue`がrootを`noBackupFilesDir/raw-captures`へ固定し、
外部から任意rootを受け取らない。実DNG/仮JPEGのpathと書込み検証はT010の別責務。

1 process・1撮影につき1つのjournal/writerを保持し、同じinstanceの更新を直列化する。複数instance/process間の排他は提供しない。
更新時は再読してexpected revisionを照合する。重複の同一受領は書込み不要、異なるbyte数や旧revisionは競合として止める。
書込みAPIが返った後も全台帳byte列を読戻し確認してから成功を返す。commit後に応答を失った場合は再読して状態を確認し、
旧revisionで上書きしたり、commit済みかもしれない内容をrollbackしない。
process death後は`CAPTURING`だけを`INTERRUPTED`に更新し、記録済み受領を保持する。
中断後の正しい遅延callbackはその形式の受領を保全できるが、中断表示を自動で成功へ昇格させない。

`AndroidAtomicJournalStorage`は標準AtomicFileを使用し、readは上限付き、肯定的な不在だけをnullとする。
書込み中に失敗したら`failWrite`、`finishWrite`呼出後は結果不明としてrollbackせず扱う。
このadapterの実端末でのfsync/容量不足/電源断確認は、fake backendの故障注入テストとは別に残す。

### 実ファイル保全（T010・T011のファイル部分）

`RawCaptureStore`は信頼できる内部rootと撮影IDを受け取り、撮影ごとのdirectoryを排他的に新規作成する。
directory作成後・台帳commit前に失敗しても、そのdirectoryを空という理由で再利用しない。
新しいwriterは同じstoreで新規作成の成功を確認した撮影だけに許可し、再生成や作成commitの応答喪失から開き直さない。
`original.dng`/`provisional.jpg`は標準Files APIの`CREATE_NEW`/`NOFOLLOW_LINKS`で開き、既存出力をtruncateしない。
root・撮影directory・台帳sidecar・両media pathのredirectを拒否する。単一process・撮影ごとに1つのlive storeを所有し、
同じroot/IDのstoreを並立させない。台帳backendも同じroot/IDに対応させる責務は将来のcomposition側にある。

出力stream handleはstoreが形式ごとに所有する。write/flush/close失敗はhandleに残り、close再試行で解放できても
受領可能には戻らない。成功callback確認後に呼出側がcloseし、所有handle・正常close・非empty regular fileのサイズを
確認してから受領台帳へcommitする。closeだけでは成功callbackと扱わない。中断はwriter解放と別で、
既に開いた正しいhandleの遅延受領は保全するが`INTERRUPTED`を成功へ変えない。

復旧部品は旧processのwriterが消えた後、新しいstoreから呼ぶ。記録済み形式の存在/サイズを照合し、
不一致は台帳を変えず停止する。台帳のない既存directoryは空でも不明として停止する。
未記録ファイルを長さから受領済みへ昇格せず、空/途中/未確認ファイルも削除しない。
台帳commit失敗/応答喪失後は同じ閉鎖handleと再読したrevisionで照合し、ファイルを作り直さない。

同期IOは撮影優先のIO executorで使う契約。アプリの固定root、CameraX stream/callback、foreground走査と撮影busyは下記で接続する。
全バイトhash、共有引渡し/一時DNG解放は下記の別処理、DNG decodeは別担当である。
stream closeとJVM上の再生成テストを、実端末のfsync/電源断・OS kill試験の合格に置き換えない。

### 共有引渡し前の内容識別と読戻し照合（T012/T013の前段）

`DngContentVerifier`はread-only openerを1回呼び、自身がstreamをcloseする。64KiB bufferで全byteをSHA-256へ通し、
期待サイズとの過不足、read/close失敗、読取が進まない状態は成功にしない。停止はopen前、readの間と直後、close後に協調確認する。
既にblockedなprovider readを即時中断する保証はない。`verify`は期待size/hashと全byteが一致したときだけ返し、
コピー先readbackにも利用できるが、URI所有/pending/公開済み・共有原本の確立を検証するものではない。

immutableな`OriginalFingerprint`は正のbyte数と厳密な小文字64桁SHA-256を持つ。最初のhashはその時点の内容識別を設定するもので、
撮影直後から最初のhashまでの同サイズ改変やDNG decode有効性を証明しない。一度設定したhashを再計算値で上書きしない。
以後の`verifyOriginal`で不一致なら停止し、旧台帳と現ファイルをそのまま残す。

受領台帳の版2はDNG受領サイズに対応する任意の原本SHA-256を追加する（flag 1byte＋存在時32byte）。
台帳payload自体の破損検出digestとは別の値である。版1はhashなしとして読取り、読取りだけでは書換えない。
次の正当な更新で版2へ移行し、受領/中断/遅延callbackでもfingerprintを保持する。未知版・不正flag・短いdigestは上書きしない。

`RawCaptureStore.verifyOriginal`はDNG受領記録と固定pathを短いlockでsnapshotし、lock外でread-onlyにhashを計算する。
その後、path/受領サイズ/revisionを再照合し、fingerprintを既存のreadback付き台帳更新でcommitする。
hash中のJPEG受領などでrevisionが進んだ場合、古い結果をcommitしない。停止callbackはstoreのlock外だけで呼ぶ。
最終の協調停止確認とmetadata commitは一括transactionではなく、その後の停止を原子的に排他する保証はない。
これは撮影優先IO executorから明示的に使う部品。通常の原本readは下記と同じleaseを取得する。起動走査と撮影接続は未実装。

### pending DNG行の確保とURI復旧（T012/T013のinsert部分）

`DngPublicationJournal`は同じ撮影の`ORIGINAL`操作を`dng-publication.bin`へ保存する。
受領台帳の`receipt.bin`とは別で、`AndroidAtomicJournalStorage`のfile kindを固定enumから選ぶ。
defaultは従来の受領台帳のまま。両file kindのbase/`.new`/`.bak`と撮影directoryに同じpath検証を適用する。
版2・4KiB上限・payload SHA-256、capture ID、revision、token、原本fingerprint、期待volume/path/MIME/name、
phaseと任意URIを記録する。版1の既存4状態を読み、token/URIを維持して次の正当な更新で移行する。

prepareは同じ撮影のhash記録済みreceiptを要求し、再prepareでも既存状態・tokenを維持する。fingerprint不一致は停止する。
1 process・1公開操作に1つのlive journal/coordinatorを所有し、他instance/process間の排他は提供しない。
`PREPARED`→`INSERTING`の台帳readback成功後だけ、1回insertを発行する。URI記録commit失敗/応答喪失時は再読し、
`INSERTING`では同じ期待metadataの候補だけを照合する。候補0/複数、情報不明、URI/volume/owner/name/path/MIME/pending/trash不一致は
`UNKNOWN`に止め、再insertや削除をしない。通常のinsert/query例外やnull応答も不明として自動停止する。
`ROW_RECORDED`も毎回再照合し、公開済み・欠落・外部変更を新しい行で補わない。`UNKNOWN`は自動解除しない。

`MediaStoreDngPendingRows`はAPI 29以上の標準Images/external_primaryへ`IS_PENDING=1`でinsertする。
API 30以上は`QUERY_ARG_MATCH_PENDING=MATCH_INCLUDE`、API 29は`setIncludePending`でpendingを明示的に含める。
owner列のnull・visibility制約・列欠落・null cursor/権限不足を安全な所有/不在と推定しない。名前/tokenだけで所有と扱わない。
広範囲権限、fallback query、row deleteは追加しない。照合結果は瞬間の観測で、恒久的な書込み許可ではない。

`ROW_RECORDED`はpending行の特定までで、byte書込み・共有保存成功・公開成功を表さない。

### コピー・公開確定・一時DNG解放（T012/T013/T023/T024）

`DngHandoffCoordinator`は公開台帳と`RawCaptureStore.handoffSource(journal)`、上記の標準MediaStore adapterを受け取る。
1撮影につき各instanceを1つ保持し、storeを台帳へbindしてから原本readerを公開する。同期IOをUI/CameraX callback threadで呼ばない。
`COPYING`を先にcommitし、64KiB上限で入力hashを照合しつつ同じpending URIへcopy、両stream close後の全byte readbackを行う。
再開時は完成内容を確認できれば再copyしない。内容不一致と全close成功を確認した自分の未完成pending行だけ再copyし、
read不能やclose失敗を空ファイルと推定しない。失敗したcloseはhandleを保持し、`releaseResources`の再試行が成功するまで追加IOを止める。

`BYTES_COMPLETE`を再照合し、`PUBLISHING`の台帳readback後にだけpending解除を行う。updateはowner/期待metadata/pendingを条件とし、
更新数1を要求する。成功応答を失っても同URIのpending=0と全byte一致を採用し、公開行をtruncate/再insertしない。
同URIの公開内容を再照合して`PUBLISHED`、`SHARED_READY`を順にcommitする。公開済み以後の不在/改変では履歴を保持して停止する。

全local readerは`verifyOriginal`も含めleaseを取得する。停止要求では減らさず、実close成功を待つ。
writer/readerが0の時だけ所有予約を取得し、その間は通常readerを新規openできない。内部原本と共有先を再検証し、
`LOCAL_RELEASE_PENDING`のcommit/readbackと共有先の再検証後だけ固定`original.dng`を削除する。
commitの応答喪失ではその呼出し内で削除せず、復旧で台帳と同URIを再照合する。削除後の不在と共有内容を確認して`SHARED_ONLY`をcommitする。
再生成時も台帳をbindし、永続release pending以後の通常reader禁止を再構築する。仮JPEG/別撮影/共有行は削除APIへ渡さない。
共有先検証と一時削除は外部アプリに対する原子transactionではない。

この引渡し部品単独のJVM検証では実一時ファイル、fake provider、故障台帳でphaseごとの前後/readback失敗、
copy/read/close、公開応答喪失、解放予約、削除前後の応答喪失を検証する。OS kill・電源断とPixel実撮影の合格には数えない。

### RAW撮影接続（T014/T015）

新しいRAW/RAW+JPEGだけを`noBackupFilesDir/raw-captures`の専用streamへ接続する。JPEG単独と動画は既存経路を使う。
固定版CameraX 1.6.2の提供OutputStreamは呼出側がcloseする。success callbackで該当streamを正常closeし、
受領台帳をcommitした後にだけ受領済みとする。エラー/中断で既存cleanupを呼ばず、未確認/途中ファイルもその撮影内に保持する。
CameraX内部で完成ファイルを生成する一時領域や、その前のsensor処理は変更しない。アプリは提供streamへの出力以降を保全し、
callback前のファイル完成やprocess kill前の受領成功を推定しない。
request errorでcloseした後に正しいlate successが来た場合も、その形式の正常closeと台帳で保全し、撮影全体を成功へ昇格しない。

`AndroidRawCaptureQueue`の1 process所有者が撮影ごとのstore/journal/coordinatorを保持し、Activity再生成で並立させない。
準備は専用executor、close/受領台帳は2 thread pool上の撮影別serial queue、復旧走査は別低優先executorとする。
hash/copy/公開は別の単一handoff IOへ分離し、初回hashのreadも失敗closeを保持・再試行可能にする。
未解放readerがある間は単一attemptの所有を維持して追加IOを止める。blocked closeで次のprepareをqueue上で塞がない。
foreground ownerが0の間、撮影/録画・Finalize/設定準備・rebind・未解放writerがある間はhandoffを新規開始しない。
予約とhandoff admissionを同じ短いlockで直列化し、開始済みattemptへの停止はlatchする。blockedなprovider IOの即時停止は保証しない。
全形式の受領台帳commitを撮影busy解除の基準にし、共有保存結果は後から別通知する。旧epoch/別撮影の通知で現在UIを変更しない。
foreground復帰では固定root内だけを走査し、既存のlive所有者を再生成しない。旧processのCAPTURINGはINTERRUPTEDへ戻すが、
未記録ファイルをサイズだけで受領済みとせず、既知DNGだけ共有引渡しを再開する。UNKNOWNは自動解除しない。
準備の期限は撮影未開始として止め、issued後の期限は結果不明としてUI待ちを解除してもcontrol所有を保持する。
実writerのclose前にhandoff予約を解放せず、Stop/late terminal callbackで復旧する。AUTOのlate全受領はcontrol readinessを戻し、
manualは既存のpreview再受付/実測を待つ。`RawCapturePresentation`は新要求の設定準備開始から通知所有者を切り替え、
前撮影の共有通知で現在の表示を上書きしない。queueは弱いobserverだけを保持する。
rendererは接続せず、自動現像の有効化やPixel実機の合格はこの接続PRの範囲に含めない。

## 撮影優先と自動現像

- 開始条件をすべてANDにする: foreground、camera session未使用、capture/record/finalize/rebindなし、
  thermal許容、空き容量余裕、renderer使用可能かつ当該解像度のメモリ予算確認済み、実行中attemptなし。
- 不明な能力/熱/メモリは抑止。撮影予約と開始判定は同じ直列coordinatorで扱い、古いsnapshotで開始しない。
- 撮影操作で新規開始を即座に禁止し、実行中attemptへcancelを通知する。完了後も世代/IDを再確認する。
  CameraXのexecutor、UI thread、撮影ファイルwriterを現像に共有せず、原本保存IOを優先する。
- cancelは協調的。非協調decodeをFuture.cancelだけで停止済みとみなさない。cameraを開く操作から残る
  CPU/IO競合は実測対象。許容できなければ自動開始条件を狭めるか、分離方式を別判断し、実機有効化を止める。
- 背景化では開始禁止/協調停止し、次のforegroundで台帳を再走査。OS kill中の完了や即時の背景現像は保証しない。
- 保存queueの撮影優先と下記の現像開始policyは別。rendererは未登録で、製品の自動現像は有効化しない。

### 未接続の開始制御の契約（T006〜T008）

`rawcapture/AutoDevelopPolicy.kt`はforeground/camera session/録画・Finalize/rebind/熱/容量/renderer/メモリの
8観測値を受け取り、未確認をnullableで表す。すべての条件が安全側に確認できる場合だけ開始可能とする。
policyは実測を行わず、呼出側が最新の観測を供給する。既定は全条件が未確認で、開始不可。

`AutoDevelopCoordinator`は短いsynchronized状態遷移だけを行い、IOや呼出側callbackをlock内で実行しない。
撮影予約と開始判定を同じlockで直列化し、予約が先なら開始不可、開始が先ならそのattemptへ停止要求を記録する。
撮影予約は実行中処理の停止/解放を待たない。呼出側はcamera操作前に予約し、優先期間終了後にその予約だけを解放する。
camera/録画が続く場合は、観測を更新してから予約を解放する。

attempt/予約は参照identityで識別し、別coordinatorや旧attemptの完了で現在のownerを解放しない。
停止要求はattemptごとにlatchし、条件回復で取り消さない。`completeAfterResourcesClosed`は実際にreader/writerが
閉じた後だけ呼ぶ契約であり、停止要求や例外通知だけでは処理枠を解放しない。未知のattemptは常に停止扱いにする。
再生成したcoordinatorは旧ticket/lease/観測を復元せず、永続台帳の復旧は後続の保全実装に任せる。
将来の接続では1 processに1 coordinatorを保持し、activity/camera再生成で生存中workerの所有を回避して作り直さない。
開始許可から実際のdecode開始までの外部操作を原子的にはできず、将来adapterは開始前にも停止要求を確認する。
この制御だけでnative処理の即時停止・実機資源競合の解消・DNG保存の成立を保証しない。

## 公式資料と採用判断

- [MediaStore](https://developer.android.com/training/data-storage/shared/media): 自分の新規画像とpending公開を使う。
  アプリの台帳と共有保存は別commit。再インストール後は旧所有扱いを前提にせず選択URIで再取得する。
- [アプリ専用領域](https://developer.android.com/training/data-storage/app-specific): 仮JPEGを永続内部領域へ。
  cacheの消去リスクを避ける。アンインストール/データ消去を越える原本は共有DNGで保持する。
- [AtomicFile](https://developer.android.com/reference/android/util/AtomicFile): 小さい単位の台帳commit候補。
  Roomを先行導入せず1台帳1writerから始め、検索/transaction要件が増えた時点で再判断する。
- [MediaStore query引数](https://developer.android.com/reference/android/provider/MediaStore#QUERY_ARG_MATCH_PENDING)と
  [MediaColumns](https://developer.android.com/reference/android/provider/MediaStore.MediaColumns#OWNER_PACKAGE_NAME): pendingの明示検索とowner不明時の停止を使う。
- [Files](https://developer.android.com/reference/java/nio/file/Files): 固定pathの新規作成に`CREATE_NEW`を明示し、既存ファイルの上書きを避ける。
  [CameraX OutputFileOptions](https://developer.android.com/reference/androidx/camera/core/ImageCapture.OutputFileOptions.Builder)の
  stream入力へ接続し、[固定版1.6.2の公式source](https://dl.google.com/dl/android/maven2/androidx/camera/camera-core/1.6.2/camera-core-1.6.2-sources.jar)の
  `FileUtil`で提供streamをcloseしないことを確認した。callerのcloseと形式別callbackを別々に確認する。
- [Photo Picker](https://developer.android.com/training/data-storage/shared/photo-picker)と
  [SAF](https://developer.android.com/training/data-storage/shared/documents-files): 本人選択のread grantを用いる。
  persistableでも移動/削除等でアクセスを失い得る。DNGを必ず選べることや設定復元の保証とは分ける。

WorkManagerは今回採用しない。永続要求と画素処理の停止能力は別問題であり、撮影優先は起動schedulerだけでは保証できない。

## PR分割と検証

1. **設計PR**: 本spec/plan/contracts/tasks/検証案と原則IV・002の方針更新。アプリ変更なし。
2. **policy PR**: `rawcapture/AutoDevelopPolicy.kt`/`AutoDevelopCoordinator.kt`と対応JUnit。
   開始条件と未知値、撮影予約race、旧完了・例外・再生成を合成検証。撮影へ未接続。
3. **保全PR**: 受領台帳`CaptureJournal.kt`/`AndroidAtomicJournalStorage.kt`と、実ファイルを扱う
   `RawCaptureStore.kt`をさらに小さいPRへ分け、各故障テストを追加する。CameraProbeへ未接続。
4. **公開PR**: `DngPublicationJournal`/`DngInsertCoordinator`/`DngHandoffCoordinator`と故障テスト。
   同URI照合、共有DNGへの引渡し、一時DNG解放を実装。一般の選択URI参照は`SharedOriginalAccess`の後続責務とし、既存publisherを置換しない。
5. **撮影接続PR**: 新RAW撮影だけを切替え、形式別callback/timeout/epoch/preview復帰/原本保全を検証。
6. **#78統合PR**: 成立したrendererをadapterで接続し、非公開実DNGと性能測定後に自動現像を有効化する。

全段階で最終headの必須CI、canonical `@codex review`、独立明示OK、thread0を確認し、mergeは別承認。
詳細な故障点は[quickstart.md](quickstart.md)。最初の設計に画素処理や試験用の大規模基盤を入れない。
