# 背景保存の独立契約試作（製品未接続）

対象main: 57bc2cf（#171）。関連範囲: #81 / #89 / #143。既存specs/038のFGS選択を再利用する。
新しいService、Manifest、権限、通知、UI、画素処理、保存挙動は追加・変更しない。
Kotlinファイルはapp source set外にあり、APKへ入らない。公開・merge・配布は今回対象外。

## 並走範囲と共有境界

今できるのはService寿命と保存owner寿命を区別する純粋契約と試験、後続adapterの設計。
ExportLifetimeは重複する同時要求を拒否し、取消・撮影開始・危険な資源状態・OS期限で停止を要求し、
実資源の解放確認までprocess内gateを保持する。既存coordinatorのslotや永続保存状態を代替しない。
終了後の同じ要求は、必ず同じ既存owner/journalへ戻す。gate自体には永続的な重複防止能力はない。

| 境界 | 現在の責務 | 後続の変更と影響 |
|---|---|---|
| ManualOriginalTrialSaveOwner / JpegPublicationJournal | 原本・世代・proof・公開target固定、intent後再現像禁止 | 原則再利用。Service専用の第二journal・queueを作らない |
| AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle | manualOriginalIo上でslot取得、advance、cleanup、解放確認後complete | Activity外の明示export実行入口が必要。UI callbackとの合意前には変更しない |
| DevelopExecution / AutoDevelopCoordinator / AutoDevelopPolicy | 前景必須、撮影予約、単一slot、停止と実解放を分離 | 対象世代に限定したexport権限が必要。foreground=true偽装や全体blocker解除は禁止 |
| PhotoRollResourceObserver / PhotoRollObservationAuthority | Activityのstart/stopに連動して観測 | service存続中の観測ownerを合意。複数observerの古い値で安全条件を上書きしない |
| PhotoRollActivity / Screen / JpegSave / ManualTrialJpegSaveOwner | UI coroutineが保存と取消を所有 | #176〜179担当と保存要求・状態購読・取消の契約を先に合わせる。今回編集しない |
| 新Service / AndroidManifest / 通知文字列・アイコン | 未実装 | 許可・ユーザー可視通知・取消・起動失敗・timeout・終了を一括接続する前に対象と影響を報告 |
| Original24Attempt / RAW・Orton等 | 画素処理と実資源回収 | RAW担当所有。Future取消/処理戻り値だけを解放証拠にしない |

## 導入順序

1. この無接続試作で重複・旧token・取消・解放未確認・復旧分類を検証する。
2. 既存唯一のqueue/slotに、immutableなcapture+generation限定export権限と観測ownerの引継ぎを接続。
   callbackから切り離された同じ保存handleを保持するadapterを設計する。変更ファイルをUI/RAW担当と合意する。
3. 明示的な前景Save操作からServiceへhandoffし、通知と取消、API別type、起動失敗時のUI復帰を接続。
   Service登録・権限・通知の追加と保存挙動変更はこの段階。timeoutはOS期限内のstopSelfと既存slot保持を両立させる。
4. 合成Androidで起動失敗・再配達・多重操作・timeout・capture予約・復帰を検証し、実機項目はまとめて別途確認する。

## 復旧と取消

| 確認できる状態 | 許される次操作 |
|---|---|
| intentなし（検証済み） | 前景の明示保存を待つ。Service再起動だけではrenderしない |
| intentのみ、生成/close不明 | HOLD。同世代も新世代も自動再現像しない |
| 同processの検証済みprivate proof | 同ownerを維持して既存publisherを照合。新renderしない |
| source/recipe/fingerprint一致の既存journal | 同token/同targetの既存publisherだけで照合。PUBLISHEDを含め原本と行を再確認 |
| UNKNOWN、壊れた/読めない/一致しないjournal | HOLD。新しいMediaStore行を作らない |
| 取消後のFD/native/GPU/worker解放が未確認 | 同processのslotとownerを保持しcleanupのみ。次の原寸処理を開始しない |

Service timeoutやonDestroyは実資源解放の証拠ではない。process death後はメモリ上のgate/proofを信用せず、
既存永続journal/intentから再分類する。Service自動再始動・START_STICKY・自動retryで新renderしない。
Serviceが消えても残った同process workerの所有権はprocess側に保持する設計が必要。今回の試作はこれを実装しない。

## 利用体験の限界

画面を離れて保存を継続することと、保存・撮影・別写真の現像を同時に動かすことは別の要件。
撮影予約は保存の停止を要求し、単一slotは実解放まで空かない。UIの閲覧や値操作を継続できても、
プレビュー再現像・保存設定の変更が並行できるとは約束しない。保存済み要求のrecipeは固定する。
同じ原本の世代更新は既存canSupersedeによりSAVING/HOLD/UNCONFIRMEDで拒否される。
撮影で原寸生成が中断されintentだけ残ればHOLDになり得るため、「撮影を続けても保存が自動再開」は現契約では不可。
この体験を変えるには破棄確認と新attemptの別設計が必要で、この試作では拡張しない。

実機候補: 保存中のホーム/復帰、通知許可拒否と通知取消、撮影予約での中断、熱/低メモリ、
process kill後のintent-only/HOLD・journal照合、多重Save、12MP/24MPの実解放、プレビューと保存recipe一致。
実機・写真操作は今回実施しない。純粋試験成功はAndroid統合・process death安全性の保証ではない。

## 純粋試験結果

Kotlin 2.4.20 / JDK25、既存のローカル依存cacheで2つのktファイルを直接コンパイルし、
`spike.backgroundsave.ExportLifetimeTestKt`を実行。6シナリオ成功。
明示操作なしの拒否、同一/異なる保存の多重要求拒否、全停止理由と解放未確認、旧ticket、
同じoperation名の別owner ticket、永続復旧分類を確認した。コンパイラheap512MiB、試験heap128MiB。
Gradle・nativeビルド・AVD・実機は使っていない。Android Serviceとしての実行試験ではない。
