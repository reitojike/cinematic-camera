# 外部DNG取り込みの純粋契約（#101 partial）

`DngImportOwner` の同期コアを、`AndroidDngImports`・`DngImportPanel` から標準SAFとMediaStoreへ接続する。Draft PRの接続案であり、実機/実写真の取り込み対応完了を意味しない。

## 保護する契約

- 外部原本はread-only portだけ。原本削除API、内部恒久DNG、decode/画素処理を持たない。
- 固定targetの共有pending行へ全byteコピーし、closeとreadback/hashを確認してから公開。公開後も同じURIを照合する。
- `READY` はbyteコピー/公開の完了で、現像互換ではない。編集非対応でも原本は保持する。完成した共有URIだけ既存の編集入口へ渡す。編集画面/recipe自体は変更しない。
- insert前に`INSERTING`を永続化。応答不明/再起動後は同targetをfindし、候補0/複数/不明で再insertしない。
- `PUBLISHING`を公開前に記録。公開済みの完全原本は再publish/再insertしない。公開と取消が競合しても公開原本を削除しない。
- 取消要求だけで終了しない。未完行回収は`DISCARDING`のcommit後、自作pendingへ限定。削除応答喪失時は同URIを再照合する。公開原本や外部原本を回収しない。
- `DngIoResources`を再利用し、close未確認なら同ownerを保持。primary失敗にcleanup失敗をsuppressedで追加し、新effect開始前に解放を再確認する。

## 状態とjournal

`PREPARED → INSERTING → COPYING → VERIFIED → PUBLISHING → READY`。
取消は`stopRequested`を保持し、公開前は`DISCARDING → CANCELLED`（insert前なら直接CANCELLED）。UNKNOWN/MISSINGは`HOLD`結果で直前のdurable phaseを維持する。source grantが利用不能なら、未完成コピーは`WAIT_SOURCE`。完全なpendingはsourceなしで全byte検証後に進められる。

`DngImportJournal`は既存`AtomicJournalStorage`のatomic replace/readback境界を使う。別magic/version、checksum、最大4096 bytes、operation/revision、固定target、source URI、fingerprint、grant観測、phase/取消意図だけを保存する。撮影の`CaptureReceipt`や原本解放phaseとして偽装しない。UTF文字を扱うが有界metadataを超えるURIは受付拒否。写真bytes/EXIF/ログを格納しない。

単一process ownerと単一executorは`AndroidDngImports`が所有する。コアは同ownerへの再入を拒否するが、同journalの別ownerを無制限に生成する機構ではない。`advance`は明示的な初回実行/再開専用で、自動再試行/起動時の自動コピーではない。journalを外部から変更する操作はownerがidleで全資源解放済みのときだけ行う。

`requestStop`はメモリ上のsignalであり、`advance`がstopRequestedをcommitする前のprocess deathでは取消意図の永続化を保証しない。UIはsignal送出を「取消完了」と表示してはならず、停止/回収/commitの結果を待つ。復旧時の勝手な再開は行わない。

## ポートと既存実装の境界

- `DngImportSource`: URIをread-onlyでopen。`DngImportRows`: target限定insert/find/inspect/read/pending write/publish/discard。`MediaStoreDngPendingRows`は撮影と共通のtarget/owner/pending照合を使い、更新/取消削除にも条件を付ける。fake成功はprovider原子性の証明ではない。
- `DngContentVerifier`の64KiB stream/全hash/短読・余剰・close契約を再利用。source byte上限64MiB（既存編集上限）は維持する。
- `DngHandoffCoordinator`は撮影受領・内部原本削除を含むので呼ばない。撮影codec/受領契約は変更しない。共有行adapterの照合のみ共用する。
- grantはSESSION_ONLY/ALREADY_HELD/ACQUIRED/UNAVAILABLEを記録。READのみを要求し、PERSISTABLEが提示された場合のみ取得を試す。取得前にACQUIRED意図を記録し、拒否時はSESSION_ONLYへ戻す。既存grantを解放せず、今回取得したgrantのみ完了/取消後に解放する。同URI再選択では所有を維持し、別URIへの付け替え前に旧所有grantを解放する。revokeはread失敗として停止し、同じfingerprintの再選択で同targetを再利用する。
- `spaceAvailable`は既存リソース観測と外部primaryの空き容量を使い、必要bytesと既存64MiBの余裕を確認する。新しい制限値・timeoutは追加しない。新規copyは既存`DevelopExecution`の撮影優先/資源受付を通す。取消・資源回収は新規copy受付から分離し、低容量でも既存pendingを回収できる。

## 実接続の境界

- ACTION_OPEN_DOCUMENT / CATEGORY_OPENABLE / EXTRA_LOCAL_ONLYで端末内・SDの候補を選ぶ。書込み権限・広域権限を追加しない。サイズ不明でも64KiB streamで実byte数とhashを確認し、64MiB超過/空/不進行/close失敗を拒否する。拡張子は選択候補の条件で、現像互換の証明にはしない。
- `noBackupFilesDir/dng-imports`の小さいAtomicFile journalだけを保持する。DNG bytesの内部恒久複製は作らない。既存記録のfingerprintから同素材を再発見し、完成原本の全byte再照合または同pendingの明示再開へ進む。未知公開・未知insertで再insertしない。
- 起動/画面表示はmetadataを読むだけで自動copyしない。確実に空のcommit前operation directoryは無視できるが、未知ファイル/壊れた記録/redirectは保留する。未完了行は公開一覧に出さず、取り込みパネルで再開/取消する。
- 背景化・退出・撮影側停止は協調中断。画面世代を失効させ、遅いREADYを自動的に編集へ渡さない。閉じるまで同owner/tokenを保持し、公開済み結果は明示操作で再検証する。取り込み用FGSは追加しない。
- コピー成功と現像対応は別。完成原本は共有領域に保持し、既存編集経路の理由表示を使う。アプリはクラウド送信/バックアップ設定変更を行わないが、共有写真はGoogle Photos等のユーザー設定によりバックアップ対象になり得る。
- [SAF公式手順](https://developer.android.com/training/data-storage/shared/documents-files)と既存MediaStore/AtomicFile APIを使用する。providerがブロックするreadの即時停止は保証しない。

## JVM試験と限界

`DngImportContractTest` は小さいbyte fixtureとメモリjournal/rowsだけを使う。sourceアクセス失効/SESSION_ONLY、容量不足、insert/publish/回収後の応答喪失・死亡、行消失・候補複数、copy改変/短読/余剰、source/output/readback close失敗、copy中取消、公開直後取消、同内容別URI再選択、stale revision、codec破損/未知版/上限、同owner再入を確認する。

```
./gradlew :app:testDebugUnitTest --tests dev.cinematic.p0.rawcapture.DngImportContractTest --no-daemon
```

`DngImportReadGrantsTest`は取得拒否・既存権限・再選択・revoke・取得直後死亡の意図・release失敗をfakeで確認する。`DngBoundedSourceTest`は未知サイズ・上限・空・close失敗・不進行を確認する。

`DngImportStorageAndroidTest`は明示引数`dngImportStorage=true`とemulator限定で、小型合成DNGの共有1行公開/再読取、公開応答喪失後の同URI復旧、pending取消/空directory復旧を実行する。自作targetだけ回収し、既存データを消去しない。実行結果はPRに記録し、クラス存在だけで合格にしない。

未確認: 実SAF providerの権限ダイアログ/取得直後死亡、実SD抜去、OS pending期限切れ、画面遷移の実操作、実機/fp実写真。fakeの行消失/権限失効は契約試験でありAndroid現象の実証ではない。今回のAndroid合成試験はMediaStore/AtomicFileの試験であり、SAF pickerの自動UI試験ではない。

GPU準備待ちの10秒はcopy時間ではなく、今回新しいtimeout・権限・依存・上限緩和を導入しない。`requestStop`は協調要求で、ブロック中readの即時終了は保証しない。snapshot/保存の製品guard・画素式も不変。

共有領域の写真は別アプリの設定でbackupされ得る。アプリはクラウド送信/backup設定変更を行わない。取り込み完了をbackup完了にしない。#101のfp実写真互換・SAF/SD抜去実機確認等は残し、partialとして追跡する。
