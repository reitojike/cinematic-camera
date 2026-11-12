# 外部DNG取り込みの純粋契約（#101 partial）

本体UI・SAF・MediaStore・権限への接続は未実装。`DngImportOwner` はfake portで検証する同期コアであり、製品の取り込み対応完了を意味しない。

## 保護する契約

- 外部原本はread-only portだけ。原本削除API、内部恒久DNG、decode/画素処理を持たない。
- 固定targetの共有pending行へ全byteコピーし、closeとreadback/hashを確認してから公開。公開後も同じURIを照合する。
- `READY` はbyteコピー/公開の完了で、現像互換ではない。編集非対応でも原本は保持する。編集画面/recipeは既存経路を使う予定で、今回追加しない。
- insert前に`INSERTING`を永続化。応答不明/再起動後は同targetをfindし、候補0/複数/不明で再insertしない。
- `PUBLISHING`を公開前に記録。公開済みの完全原本は再publish/再insertしない。公開と取消が競合しても公開原本を削除しない。
- 取消要求だけで終了しない。未完行回収は`DISCARDING`のcommit後、自作pendingへ限定。削除応答喪失時は同URIを再照合する。公開原本や外部原本を回収しない。
- `DngIoResources`を再利用し、close未確認なら同ownerを保持。primary失敗にcleanup失敗をsuppressedで追加し、新effect開始前に解放を再確認する。

## 状態とjournal

`PREPARED → INSERTING → COPYING → VERIFIED → PUBLISHING → READY`。
取消は`stopRequested`を保持し、公開前は`DISCARDING → CANCELLED`（insert前なら直接CANCELLED）。UNKNOWN/MISSINGは`HOLD`結果で直前のdurable phaseを維持する。source grantが利用不能なら、未完成コピーは`WAIT_SOURCE`。完全なpendingはsourceなしで全byte検証後に進められる。

`DngImportJournal`は既存`AtomicJournalStorage`のatomic replace/readback境界を使う。別magic/version、checksum、最大4096 bytes、operation/revision、固定target、source URI、fingerprint、grant観測、phase/取消意図だけを保存する。撮影の`CaptureReceipt`や原本解放phaseとして偽装しない。UTF文字を扱うが有界metadataを超えるURIは受付拒否。写真bytes/EXIF/ログを格納しない。

単一process ownerの生成は将来の接続側の責務。コアは同ownerへの再入を拒否するが、同journalの別ownerを無制限に生成する機構ではない。`advance`は明示的な初回実行/再開専用で、自動再試行/起動時の自動コピーではない。journalを外部から変更する操作はownerがidleで全資源解放済みのときだけ行う。

`requestStop`はメモリ上のsignalであり、`advance`がstopRequestedをcommitする前のprocess deathでは取消意図の永続化を保証しない。将来UIはsignal送出を「取消完了」と表示してはならず、停止/回収/commitの結果を待つ。復旧時の勝手な再開は行わない。

## ポートと既存実装の境界

- `DngImportSource`: URIをread-onlyでopen。`DngImportRows`: target限定insert/find/inspect/read/pending write/publish/discard。将来adapterは毎操作で同じtarget/owner/pendingを検査し、mutating APIでも条件を制約する必要がある。現在のfake成功はprovider原子性の証明ではない。
- `DngContentVerifier`の64KiB stream/全hash/短読・余剰・close契約を再利用。source byte上限64MiB（既存編集上限）は維持する。
- `DngHandoffCoordinator`は撮影受領・内部原本削除を含むので呼ばない。既存撮影コード/codecを変更していない。
- grantはSESSION_ONLY/ALREADY_HELD/ACQUIRED/UNAVAILABLEの観測値だけ。権限取得/releaseを実装しない。取得失敗でも現在read可能ならSESSION_ONLYとして扱える。revokeはread失敗/UNAVAILABLEを接続側が通知し、同素材再選択はfingerprint一致時だけ同targetを維持する。
- `spaceAvailable`は必要bytesに対する外部admission port。容量不足/観測不能はfalse。容量予約や安全余裕の新値を導入せず、copy中失敗も安全側へ止める。撮影優先/OS資源受付は将来の接続側に残る。

## JVM試験と限界

`DngImportContractTest` は小さいbyte fixtureとメモリjournal/rowsだけを使う。sourceアクセス失効/SESSION_ONLY、容量不足、insert/publish/回収後の応答喪失・死亡、行消失・候補複数、copy改変/短読/余剰、source/output/readback close失敗、copy中取消、公開直後取消、同内容別URI再選択、stale revision、codec破損/未知版/上限、同owner再入を確認する。

```
./gradlew :app:testDebugUnitTest --tests dev.cinematic.p0.rawcapture.DngImportContractTest --no-daemon
```

未実装/未検証: SAF選択・grant取得/releaseと取得直後死亡、未知サイズのbounded identify、複数operation間の既存原本索引/重複照合、実MediaStore条件付き回収、UI/lifecycle/FGS、永続ファイルadapter、providerでブロックするreadの解除、OSによるpending期限切れ。fakeの行消失は期限切れ現象の契約試験でありAndroid実証ではない。未知サイズのsourceを推定fingerprintで渡してはならない。

GPU準備待ちの10秒はcopy時間ではなく、今回新しいtimeout・権限・依存・上限緩和を導入しない。`requestStop`は協調要求で、ブロック中readの即時終了は保証しない。snapshot/保存の製品guard・画素式も不変。

共有領域の写真は別アプリの設定でbackupされ得る。今回のコアはクラウド送信/backup設定変更を行わない。取り込み完了をbackup完了にしない。#101のfp実写真互換・SAF接続・SD抜去実機確認等は残し、partialとして追跡する。
