# 設計
プロセス内のMutexを呼出側の原本確認からrender/結果再照合までの区間に適用する。LaunchedEffectは新しいキーで前jobを取り消すため、取消されたMutex待機は標準coroutinesにより除外される。新しいexecutorや手動の待機キューを増やさない。既存CancellationSignalの通知と画素チェックポイントを変更しない。

旧workerのblockが非協調的に動いている間もMutexを保持する。旧結果は従来のticket確認とpendingPreviewのfinally回収が扱う。Mutexはnative解放を代行するものではなく、既存DevelopExecution/queueの安全確認を省かない。

計測はPreviewTimingログのserialフラグで区別する。既定オフ、`adb shell setprop log.tag.PreviewSerial DEBUG` で診断時のみ有効、終了時INFOへ戻す。実機操作は別の承認済み機会にのみ行う。

## 既存futureの終了との関係
Mutexだけでは、adapterの取消待ちが先に戻り、queue側のfutureが未完了のまま次の要求を通す可能性がある。候補をMANUAL_TRIALだけに限定し、render/prepareのawaitを取消したときも、既存queue producerのfuture終了までIO呼出側で待つ。future.cancelを解放証拠として使わず、producerの完了/例外と既存の回収を待つ。通常モードは元の早期復帰を維持する。
これにより非協調的な既存処理が残っている間は最新要求も待つ。停止点の追加や待ち時間の上限保証はしない。queueが停止した場合に安全確認を飛ばして次を通すこともない。

## Standard implementation references
- Kotlin Mutex: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.sync/-mutex/
- Compose effect cancellation and restarting: https://developer.android.com/develop/ui/compose/side-effects
- CompletableFuture completion: https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/CompletableFuture.html

The Android synthetic fixture verifies coroutine replacement, not touch-to-photon latency. It does not establish physical-device adoption or the entire PhotoRollScreen interaction contract. Existing source verification, revision tickets and Bitmap ownership remain authoritative.
