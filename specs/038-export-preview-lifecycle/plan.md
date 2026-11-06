# 設計判断・レビュー用メモ

## 復帰時に再現像していた原因と最小変更

PhotoRollActivity.onPause、PhotoRollScreen.ON_PAUSE、RESUMED再入場がそれぞれ入力/表示を破棄する。
加えてTrialStrengthEditor.pauseは編集開始値へ戻していた。取消とアプリ切替を分け、
suspendForLifecycleでは最後の提出値を保持し、未完のgestureと古い完了tokenを無効化する。

PhotoRollPreviewCacheはAndroid LruCacheをViewModelで所有し、画面再生成にも対応する。
rendererの重い処理だけを省略し、元のcheckOriginal→表示前verifyは維持する。
hitでもAndroidManualOriginalTrialSaves.prepareから現行の保存handleを取得する。
cacheに旧Activityのresources closureやPhotoRollJpegSaveOwnerを入れない。
表示コピーとキャッシュコピーは別のBitmapで、Composeが前者をrecycleしても後者に影響しない。
trimとworkerの競合はepochで囲み、clearより前に開始した仕事は再登録できない。

8MiBはキャッシュ保持上限でありプロセス総量ではない。UI側の別コピーは最大さらに8MiB、
コピー時の一時所有も存在する。原寸実行のメモリ改善を主張しない。raw入力の既存破棄は維持。
Glide等への移行は独自現像・immutable保存世代との接続範囲が大きいため今回不要。

## 原寸ownerと状態遷移

UI PhotoRollJpegSaveOwner → ManualOriginalTrialHandle → queue.manualOriginalIo →
DevelopExecution/AutoDevelopCoordinator slot → ManualOriginalTrialSaveOwner →
Original24Attempt/resources → JpegPublicationJournal/Publisher。

| 境界 | 現在の処置 | 再試行 |
|---|---|---|
| 保存要求前/intent前の取消 | READY、重いIOなし | 同じ要求で開始可能 |
| durable intent後、生成/closeの証拠なし | HOLD | 新規render不可。schedulerの自動retry不可 |
| 検証済みprivate proofが同じprocessにある | UNCONFIRMED | 同じproof/publisherで照合、再renderしない |
| durable publication journalがある | 元のpublic targetを復元 | 同じ行を検証/照合。未知はHOLD/UNCONFIRMED |
| 生成中のprocess death、intentだけ残る | HOLD | 自動で別世代や別MediaStore行を作らない |
| FD/stream/GPU等の解放未確認 | slot保持 | cleanupのみ。解放確認まで次のrenderを入れない |

24MPで資源所有者を増やさない。GPU担当が導入する場合も、cancel要求と解放完了を分ける。
GPU fence/readback/native bitmap/FDの解放が肯定確認できてからslotを返すこと。
中断されたreadbackを成功JPEGや公開proofにしない。ViewModelは原寸bufferを所有しない。

## 背景保存の公式手段比較と選択

| 手段 | 適合性 | 判断 |
|---|---|---|
| Activity coroutine/既存IOのみ | 表示中だけの処理は最小。背景継続保証なし | 今回のruntimeはこれを維持し制限を明示 |
| 通常WorkManager | 遅延可能でprocess再起動後も行う仕事に適する | 現在の生成intentから自動retryできず、そのまま導入しない |
| foreground Worker | 通知付き長時間処理が可能 | 自前journalとschedulerの二重状態管理が増え、Android16以降はjob quotaも関係 |
| 直接Foreground Service | 明示Saveで即時開始、通知で継続/取消を見せる一件の処理 | 背景継続を実装する方式として選択。API35+ mediaProcessing、旧版は適切なdataSync型を検証 |
| shortService | 約3分の制限 | 12MPの単発36〜41秒から24MP・熱制限込みの上限は保証できないため選ばない |
| user-initiated data transfer job | ユーザー起点のデータ転送用 | ローカルRAW現像を転送と偽装しない |

直接FGSも今回未導入。先に必要な境界は以下。Activityを離れてもforeground=trueと偽る実装は不可。

1. 保存の唯一の実行ownerをserviceへ渡し、UIは状態購読だけにする。通知/取消を可視化する。
2. UI前景と明示export実行leaseを分ける。現在のforeground blockerを全体で無効化せず、
   同じgenerationのexportだけ許可する。camera優先・温度・メモリ・容量・権限のgateを維持。
3. Activity停止後も観測をservice側で継続する。タイムアウト/停止では同じownerへcancelを伝え、
   OS期限内にstopSelfする契約と、未解放resourceを次の仕事へ渡さない契約の両方を満たす。
4. process death後の自動再生成は行わず、journalを照合して同じ保存だけを復元する。
   再生成を許すなら、破棄確認と新attempt IDを永続化する別設計が必要。
5. Manifest/通知/サービス権限、API29〜現行の起動制限とtimeoutを合成Android試験で確認する。

今回ここを小さなcache修正に混ぜない理由は、前景条件がpreviewだけでなく撮影優先・全queue停止と
共通の安全authorityだからである。背景保存は上記の明示export leaseを含む別変更でレビューする。

## 公式資料（確認: 2026-11-06）

- [background task選択](https://developer.android.com/develop/background-work/background-tasks)
- [long-running WorkManagerとAndroid16 quota](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)
- [FGS types: mediaProcessing / dataSync / shortService](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [FGS timeout](https://developer.android.com/develop/background-work/services/fgs/timeout)
- [ViewModel/SavedStateHandleの寿命と小さい状態](https://developer.android.com/topic/libraries/architecture/viewmodel/viewmodel-savedstate)
- [Bitmap memory cache/LruCache](https://developer.android.com/topic/performance/graphics/cache-bitmap)

速度目標は作らない。1回の12MP/診断版の時間から製品性能や24MPメモリ成功を推定しない。
