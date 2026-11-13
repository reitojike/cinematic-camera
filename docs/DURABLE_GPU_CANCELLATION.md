# 通常GPU保存の確認済取消証明（#365）

request codec v2 と旧 intent を変更せず、世代ディレクトリの `manual-attempt.bin` に
request（原本URI/grant/fingerprint・captureId・generation・recipe）、GPU engine、
実行互換version、attempt、STARTED / CANCELLED_CLEAN、checksum を保存する。
対象はこの記録を導入した後の通常GPU保存のみ。診断CPU/GPU、旧intent-only、
公開不明、JPEG入口後、cleanup不明、未知engine/versionを復旧対象にしない。

## 順序と権威

1. 既存publicationがあればその照合を優先する。UNKNOWNは
   UNCONFIRMED/PUBLICATION_UNKNOWNであり、取消証明による再生成へ変換しない。
2. 初回保存では既存intentをwrite/readbackし、GPU engineを固定、STARTED(1)を
   atomic replace/readbackしてからproducerを開始する。
3. 既存のJPEG入口前取消判定は「候補」に留める。外側の最後のresource releaseと
   coordinator slotの解放成功後に、request/current-generation/intent/attemptと
   publication不存在を再確認し、CANCELLED_CLEANをreplace/readbackする。
4. 最終化したclean ownerの資源ゼロでの再解放は無副作用とする。
   証明確定後に失敗し得る後片付けを残して、メモリだけHOLDにしてはならない。
5. 再起動時はmetadataを読むだけで、producerやpublisherを開始しない。
   同設定retryは明示操作を待ち、producer前にSTARTED(N+1)を確定する。
   前attemptの証明を残したままretryを開始しない。
6. 新設定は既存の直列queue・generation fenceを使い、source単位cursorの
   次世代commit/readbackで旧ownerを失効させる。旧intent/台帳は消さない。

台帳とintent/cursorは複数ファイルであり、一括transactionではない。書込途中・ack不明は
そのプロセスではHOLD。新しいreaderはAtomicFileが復元した完全な記録とintent/currentを
照合する。旧CLEANが残るretry失効前のkillではproducerが未開始、新STARTEDが残れば
producer未開始に見えてもHOLD。新CLEAN commit後のkillは再読取で一致を確認する。
checksumは破損検出であり暗号署名ではない。旧状態や不明なengineを推定で補完しない。

## UIと別写真入力

画像入力は保存queueを待たない既存の独立workerを維持する。cold metadataは表示後に
別coroutineで読み、復元まで調整/保存を受け付けない。確認済取消では保存requestの
元設定を復元し、edit-onlyの保持値から暗黙に新世代を作らない。保存中Aがあるときの
B入力/表示をmetadata読取の待ちで塞がない。Bの保存準備は従来どおり直列queueを使う。

## 互換性と対象外

実行互換versionはアプリのversionNameから推定しない。画像処理契約の変更時には
互換表をレビューし、対応を証明できないversionはHOLDにする。request v2と旧CPUの
完成publicationは既存readerで扱い、CPU履歴をGPUとして再生成しない。D0のproducer削除と
履歴readerの保持は別の判断である。

原本・journalの削除、別URIへの複製、既存保留の自動解除、別書出しUX、CPU fallback、
画像処理・並列数・メモリ判定・配布変更を含めない。#377の公開不明照合とは別契約とする。

## 回帰の実行単位

- JVM: codecと全識別子不一致、旧schema/engine/version/attempt、write前/commit後/readback失敗、
  最終cleanup/slot解放失敗、retry前失効、世代cursorのcommit不明、publication優先。
- Android: 通常debug・小型合成のみ。`DurableCancellationRestartInstrumentationTest`を
  `isolated=true durableCancellation=true durableCase=<毎回一意の英数字ID>` で実行する。
  seedClean→host force-stop→resumeSameRecipe / resumeChangedRecipeThroughColdUiは別ID。
  seedAfterJpeg / seedLegacyIntent→host force-stop→resumeHeldも別ID。
  seedStartedAwaitHostKillは`armed.txt`を確認してからhostで対象appだけをforce-stopし、resumeHeld。
  seedとresumeのPIDが違い、間にPIDが存在しないことをhost側にも記録する。
- `Original24CancellationRetryInstrumentationTest`の通常GPU4件と
  `GpuProductInputAndroidTest.inputDoesNotWaitForAnotherPhotosExportMetadataQueue`を維持する。
- 原本hash・旧intent・正しいrecipe/世代・公開URIとJPEGfingerprint・二重producerなしを検査する。
  cross-process fixtureは自作の合成原本とjournalを証拠として保持し、既存保留の消去をしない。

共有レーンは利用前に調整し、実機・固定署名更新・24MPの実機合格に読み替えない。
実行結果と未確認事項はIssue/PRへ記録する。この文書は試験結果の代用ではない。
