# 原本・編集情報・保存の現行仕様

## 原本の保全

- DNG原本と編集情報を分離し、現像処理でDNG自体を書き換えない。既存写真の移動・削除・一括移行を行わない。
- 新しいRAW撮影は形式別の受領と保全を行い、撮影時の仮JPEGは内部に保持する。DNGは標準MediaStoreの `Pictures/Cinematic Camera/` へ引き渡す。
- 共有DNGのbyte/hash照合と台帳への引渡しが確認された後だけ、内部の一時DNGを解放する。不明・失敗・中断では受領済み原本/仮JPEGを保全する。
- 共有DNGを編集・現像の永続原本として読み取り参照する。原本検証、所有状態、権限確認を省略しない。他アプリ由来を自アプリ所有に偽装しない。

## 写真ごとの編集情報

- 表示確認済みの確定した強さ・相対色温度・明るさを、「閉じる」の受理順にアプリのno-backup領域へ保存する。DNG隣のサイドカーには書き込まない。
- 原本URI・bytes・SHA256・grant、実際に使った3値、処理recipeを照合に使う。「既定」という印だけを保存しない。
- 原子的なmetadata置換と読み戻し一致後に閉じる。小さいmetadata書込みは背景化/Activity終了後も完了させるが、古い応答で新画面を変更しない。
- 再選択後に原本識別と処理contractが一致したときだけ復元する。破損・未知版・不正値・短読・余剰bytesを拒否する。再生成時の保持済み提出値を古い永続値で上書きしない。
- 取消・未提出ドラッグは保存しない。編集値の保持はJPEG保存完了とは別。
- 原本が移動してURIが変わったときの探索、旧処理版の再生、再インストール後の復元、URI権限の新規永続取得はこの機能が保証する範囲ではない。

## 手動の原寸試験JPEG保存

- 対応範囲は原本64MiB以下、出力24,000,000画素以下、各辺8192以下などの既存検査を満たすDNG。画素数だけで全カメラの対応を保証しない。
- 原寸保存factoryはFoundation2 / Orton4 / Character2 / Glow2 / Mist2 / R7 tone2を明示し、色融合はOFFを維持する。行分担だけを変え、画素式・原本照合・取消・公開契約は変えない。低レベルrendererの直列基準、診断A/B、プレビューの既定とは分ける。
- 調整を閉じ、原本・確定値・表示結果が一致する不変の世代handleから、明示保存で原寸試験JPEGを生成する。
- 全画素のcoverage、JPEG検査、原本再照合、producer資源解放を経た手動試験結果だけ公開する。停止・Future完了・通知消失だけで成功にしない。
- 完成JPEGがある同じ世代の再試行は同じ公開操作を照合する。開始記録だけ残り成功の証拠がない復旧は保留し、自動で新しい現像やMediaStore行を増やさない。
- JPEG出力を一度も開始しておらず、CPU/GPU worker・Bitmap・FD・stream・作業コピー・失敗出力の回収をすべて確認できた取消だけ、同一プロセス内で同じ保存を明示再試行できる。取消要求だけでは許可せず、通常のadvance・通知再開・資源回収だけでは再生成しない。
- 取消後の同設定再試行は同じ原本・世代・確定設定を保持し、最初の実保存開始時に選んだエンジンを固定する。CPU fallbackは行わない。上記の確認済み取消に限り、同プロセスでプレビューを再開して設定を変更し、新世代で保存することもできる。受付時に旧ownerの取消許可・全資源解放・intent一致・publication不存在を再確認する。新request確定後は旧世代を失効させ、旧intentは消さない。取消証明のない再起動・intent不一致・解放不明・JPEG出力開始後の不明状態はHOLD、完成JPEG・公開記録がある場合は既存の再照合を維持する。
- 通常GPUの新しい保存だけ、request v2・原本・世代・engine/実行契約version・attemptを結ぶ世代別台帳を持つ。最終cleanupと保存slot解放後のCANCELLED_CLEANを原子保存・readbackできた取消は、再起動後も同設定の明示retryまたは設定変更による新世代を許す。再起動や写真openだけでは保存しない。retryのproducer開始前にSTARTED(N+1)を確定して旧証明を失効する。旧intent-only、未対応engine/version、公開不明は救済せず、旧CPU履歴・request codec v2・publication再照合を保持する。詳細は[取消証明](../../docs/DURABLE_GPU_CANCELLATION.md)。
- 過去のPUBLISHED記録だけで現在のファイルを成功扱いにせず、開く前に同じownerで再照合する。

## 保存結果不明の救済（#377・統合提案）

- 初期状態は保留。再確認で同一 target・所有者・URI・固定 render/recipe・byte hash が一致する行だけを扱う。
  旧 UNKNOWN を保持して照合 receipt と recovery journal に接続し、同じ行の公開済み照合／pending 完成の公開を行う。
- 空・部分・異内容 pending は保留し、削除・上書き・再コピーを行わない。検索ゼロ、private JPEG 不在、
  ApplicationExitInfo は過去の未公開証明にしない。
- 不明な保存は「もう一度確認」「今は保存しない」「重複の可能性はあるが保存し直す」を区別する。
  最後の明示選択だけで、旧原本・履歴を保全した独立の永続要求を同じ設定で受け付ける。
  key/次世代/renderer を固定し、旧新 owner の同時実行を拒否する。新要求も不明なら保留する。
- #365 の確認済取消とは別契約。取消証明を緩めず、自動再書き出し、URI 複製、CPU fallback、画質変更を行わない。
- この統合は未検証 checkpoint。#260 の通常配布版でメモリ判定を緩めるには、#377 全体を同版または先行で
  配布する gate が必要で、現段階は未達。詳細と残る境界は [接続契約](../../docs/JPEG_RECONCILIATION.md) を参照。

## 作業コピーの回収

- 保存のDNG一時コピー、展開RGB16、中間Float32は永続原本ではない。成功・取消・失敗とも、FD・stream・workerの解放を確認した後にその場で削除する。調査用に写真コピーは保持しない。
- 閉じる処理や削除に失敗した場合は同じownerを保持し、解放/削除を再試行する。取消要求だけで回収済みにしない。
- 再起動後の最初のqueue復旧処理で、以前の原寸保存・preview・preflightの既知作業コピーを回収してから次の処理を受け付ける。未知のファイル、redirectされたpath、判別できない所有状態では保留する。
- 失敗した未公開JPEGはwriter解放後に回収する。再起動時は一致する手動開始記録があり、公開記録が全くない再開不能世代だけを対象とする。公開/復旧に使う完成JPEG、各種journal、受領済み原本/仮JPEG、共有原本は削除しない。
- 作業領域はnoBackupFilesDir。allowBackup=false、fullBackupContent=falseとdata-extraction-rulesの除外を維持し、自動バックアップへ含めない。

## 背景での保存

- 前景の明示操作で開始した単一保存だけをForeground Serviceで継続する。画面・通知から同じownerへ取消を要求できる。
- API33～34はdataSync、35以降はmediaProcessingを使う。通知拒否でも通知提出自体を省略しない。
- 撮影予約・危険/未知の資源状態・取消・timeoutでは停止を要求する。撮影受付を保存完了待ちにしない。
- START_NOT_STICKYとprocess内tokenを使い、Serviceの再配達から自動再現像しない。Service開始失敗時に別保存経路へ暗黙に切り替えない。
- 取消要求やonDestroyと実際の資源解放は別。worker/FD等の終了を確認するまでslotを保持する。nativeの即時停止を保証しない。

## 未実装・未確認との境界

自動完成品質FULL現像のproviderは通常撮影に接続していない。型やbridge、厳密なintentが存在するだけで自動現像済みとはしない。
手動試験結果はCompletedRenderではなく、自動現像providerへ登録できない。
顔保護を含む完成品質、実機の24MP性能・全対応機種・背景保存の全中断パターンの合格は、仕様の存在から推定しない。

## 詳細契約の参照

[RAW受領と保存設計](../016-raw-capture-lifecycle/spec.md)、[原寸経路](../037-original-stage-clear-24mp/spec.md)、
[保存・プレビューのlifecycle](../038-export-preview-lifecycle/spec.md)、[背景保存](../043-foreground-export/spec.md)、
[編集情報の保持](../047-durable-edit-values/spec.md)。
016には実装済み部分と将来のFULL設計が混在するため、本書の区分を優先する。歴史的な1024px私有試験だけの制約を現在の手動原寸保存へ戻さない。

## GPU 原寸保存（#286 / #341・通常版既定化は #353 の提案）

- 共通エンジン設定を最初の実保存時に一度取得し、その保存と明示再試行の間は固定する。通常debug/releaseの新規保存はGPU、専用診断版は明示選択を使用する。既存ownerの固定engine・保存世代・原本を移行せず、低水準試験のCPU既定は変えない。engine切替だけで同じsource/recipe/generationの保存済みJPEGを再生成しない。
- 検証済み snapshot の LibRaw 原寸 RGB16 から読み取る未調整の source と、既存診断露出 EV + 手動 EV、既存相対色温度 gains を GPU に渡す。warmth は GPU 内で一度だけ適用する。RGBA8 行を既存 Bitmap に詰め、JPEG 95、出力再読取、原本の前後照合、publication owner/journal は共用する。
- GPU 保存失敗は固定の理由コードと端末内の集計件数を記録する。画面には当該保存 owner の失敗理由・件数を示し、CPU への無言 fallback はしない。写真名・URI・例外本文は失敗記録へ転記しない。
- JPEG 出力開始前の GPU 画像処理失敗で、GPU・原本・作業領域・出力の解放をすべて確認できた場合だけ、同じ画面から GPU 保存を明示再試行できる。通常の advance や通知再開だけでは再生成しない。再試行は原本を再検証・再展開し、同じ世代・設定・エンジンを使う。
- durable intent は消さない。同プロセス内の確認済み失敗だけが再試行許可を持ち、再起動、解放不明、intent 不一致、JPEG 生成後、publication 不明は既存の HOLD / 同一 publication 再照合を維持する。失敗中の新しい編集世代への変更は許可しない。
- 保存開始前に保存対象AのGPU preview ownerの終了を確認し、保存は専用GL thread/contextを所有する既存Foreground Serviceで実行する。保存対象Aのpreviewと同時描画せず、保存後は利用者が明示的にpreviewを再開する。previewのtrimでforeground exportのownerを解放しない。
- 取消は上記の共通保存契約に従う。JPEG入口前かつ全cleanup完了と確認できた同プロセスだけ、同世代の明示retryまたは設定変更による新世代を許す。不明・入口後・取消証明のない再起動はHOLDを維持する。取消要求を解放済みや公開済みの証拠にしない。
- GPU帯64/256/端数帯と全画面GPUの比較、入力/公開/ownerの安全契約は自動検査する。CPUとの数値差は参考記録のみで、Gate 2は本人の実写判断とする。[承認記録](https://github.com/reitojike/cinematic-camera/issues/291#issuecomment-6813411664) のAPK・通常保存範囲を、後続版や取消/故障・24MP・性能の合格へ広げない。通常版のGPU既定化は #353 のPRマージまで提案であり、マージと実機操作には別途承認が必要。

### GPU別写真入力と保存の所有権（#350）

- GPUでAを保存中でも、別写真Bの入力準備・表示は保存queueとは独立した単一入力ownerで行う。
  CPUの入力・描画・保存、RAW展開、画素式、単一foreground保存の契約は変えない。
- Bの表示だけで保存可能にしない。既存保存authorityから取得した同じ原本fingerprint・確定recipeの
  handleを照合してから保存操作を有効にする。Aの結果や遅延した別選択の結果をBへ流用しない。
- 入力と保存は別tokenで取消・解放を確認する。失敗closeのownerは保持し、他方の完了で解放済みにしない。
  capture優先・原本照合・既存メモリ/熱guardを保ち、入力期限を延長して競合を隠さない。
- 保存のため閉じた同じAのpreviewはREADY/PUBLISHED時の明示再開を維持する。
  別写真Bの初回入力とは区別し、保存の暗黙retry、未確定publicationの解除、二重保存を追加しない。

## 外部DNG取り込み（#101 partial・接続案）

外部原本はREADのみで取得し、内部恒久DNGを作らず共有pendingへコピーする。完成/close/readback/hashを確認してから公開する。独立した小さいjournalでinsert/publish/取消の意図を保持し、公開不明時は再insertしない。取消は自作pendingだけに限定し、公開原本・SD原本を削除しない。close不明ならownerを保持する。

未知サイズも既存64MiB上限で実byteを検証する。process復旧はmetadataを読むだけで、自動copyしない。同素材は既存fingerprint/targetから再発見する。新規copyの資源受付と取消/回収を区別する。標準SAF/MediaStore/AtomicFileを使い、新権限・新依存・バックアップ設定変更を追加しない。実機でのSD抜去/再起動とfp実写真は別の未確認項目として[取り込み契約](../../docs/DNG_IMPORT_CONTRACT.md)で追跡する。
