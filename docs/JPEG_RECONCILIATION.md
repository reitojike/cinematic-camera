# 保存結果不明の照合と別書き出し（#377、統合前の設計）

これは #377 の実装接続契約。現時点で `JpegReconciliation` は読取専用の基盤であり、
アプリの保存 owner / UI には未接続。UNKNOWN の救済や別書き出しを製品実装済みとは扱わない。

## 配布条件

#260 で通常配布版のメモリ事前判定を緩める場合、#377 の救済全体を同じ配布版、
またはそれ以前に含めることを必須とする。この読取専用基盤だけでは条件を満たさない。
計測専用版の記録のみ判定は別の診断であり、この配布条件の達成とは区別する。
この変更で production メモリ guard・画質・CPU fallback は変更しない。

## 照合の接続契約

- 呼出側は既存の直列保存 authority を保持し、固定した原本 fingerprint、capture/generation、
  recipe、renderer、target/token、URI、revision を渡す。現在の編集設定から旧要求を推定しない。
- 検索 adapter は公開済みと pending の両方を含める。権限喪失や query 障害を空リストへ変換しない。
- 読取前後に journal と current generation を再確認する。候補が唯一で、所有者・volume/path/name/MIME・
  pending/trashed・既知 URI が一致した場合だけ byte hash を調べる。読取後も metadata と候補を確認する。
- 既存 `DngContentVerifier` と `DngIoResources` を使う。close が確認できない資源は呼出側が保持し、
  cleanup 完了まで次の要求を受け付けない。例外・停止・世代変更は保留にする。
- `PUBLIC_BYTES_MATCH` / `PENDING_BYTES_MATCH` は観測結果であり、永続成功 receipt や公開許可ではない。
  永続照合記録の commit/readback と旧証拠の固定、owner 統合が別途必要。
- 空／部分／同長改変／過大 pending は `PENDING_BYTES_DIFFER`。単なる不一致から「正しい途中 prefix」や
  「安全に上書き可能」と推定しない。完全 private JPEG がある場合の同一行再開は別の証拠検証が必要。
- 候補なし・複数候補・改変・権限喪失・I/O 障害は保留。private 出力不在も未公開証明にはならない。
  ApplicationExitInfo は診断に限定する。

## 永続化基盤・既存 publisher への接続

`SaveRecoveryCodec` は別 magic/version/kind、上限16KiB、checksum、内包 blob の長さ制限と
canonical readback を持つ。照合記録は旧 journal 全体、観測分類、URI を固定する。
`JpegReconciliationReceipt` は呼出側が割り当てた一観測専用 slot に一度だけ保存し、異なる証拠で
上書きしない。再照合には別 slot が必要。slot の割当・保存先・読取結果の UI 採用は未接続。
commit 前後に既存 authority が旧 journal/revision・世代と資源解放を確認する必要がある。
永続記録は過去の観測であり、現在の MediaStore 状態や公開許可を永久に保証しない。

旧 intent / journal は保持する。照合記録には旧 journal 全体またはその厳密な識別と revision、
観測 URI と byte 証拠を固定し、旧 UNKNOWN を通常状態へ書き換えない。
同一 pending 行の再開は既存 `RecoverablePhotoPublisher` の経路を再利用し、再描画・新規 insert をしない。
公開前の原本再照合を省略しない。公開済みの後日削除・改変から自動再作成しない。
書きかけ削除や既存 JPEG 上書きは暗黙に追加しない。

## 明示的な別書き出し（受領モデルのみ、実行・UI 未接続）

初期状態は保留。「もう一度確認」「今は保存しない」「重複の可能性はあるが保存し直す」を区別する。
最後の選択だけで、旧要求との関連を持つ独立した永続要求を作る。旧保存を成功／取消に偽装しない。
原本 URI 複製や source cursor の黙った上書きで identity を迂回しない。
固定 idempotency key の commit/readback、二重タップ・kill・ack 喪失後の再取得、原本 hash 再照合、
旧新 owner の排他、再び不明になった要求の保留が接続前の条件。

`ExplicitRecoveryExport` は固定 key、旧要求、旧 intent 生 bytes の SHA-256、任意の旧 UNKNOWN journal、
同じ原本の次世代要求、指定 renderer を保持する。旧 intent の codec を移行せず、旧 bytes は外部に保全する。
旧/破損 request を現在の設定から復元してこのモデルへ投入してはならない。
`ExplicitRecoveryExports` は一 predecessor に一つの consent slot を持つ受領モデル。
同じ key/要求は再取得し、別 key/設定への置換は拒否する。replace/ack/readback 不明時は旧新とも保留する。

接続する authority は以下を全経路で守る必要がある。

- 唯一の既存直列 owner 上で、同じ predecessor の slot を全 cold-open/通知/再確認/保存経路が参照する。
  別インスタンスによる並行 writer や、slot の削除・使い回しは許さない。
- `accept` の callback で明示選択、依然不明であること（公開確定 receipt がないこと）、
  旧 intent/journal の完全一致、原本 hash、世代、指定 renderer、旧新処理の停止と全資源解放を確認する。
- 旧 writer は `oldMayRun`、新 writer は固定要求について `newMayRun` を確認する。
  両者は receipt の存在に基づく必要条件だけで、原本再照合や generation/停止/資源 fence を代替しない。
  例外は保留へ伝える。失敗を許可に変換しない。
- `accept` の戻り値は開始許可ではない。同一要求を何度取得しても、producer の一度限りの開始は
  **既存保存 owner の durable intent/readback** に接続する。kill が intent 前なら未開始、intent 後なら
  既存 owner の HOLD/同一 publication 再開規則を使い、ここで再実行可能と推定しない。
- source cursor の変更が必要なら、consent を先に commit/readback して旧 writer を fence した後、
  固定した次世代だけを既存受付へ渡す。cursor ack 喪失から別世代を採番しない。この接続は未実装。

このモデルは保存の進捗・取消・成功状態を持たず、#365 の attempt 台帳や publisher を複製しない。

## #365 との分離

#365 の通常 GPU の将来の確認済取消だけを扱う永続証明は緩めない。
最後の cleanup と slot 解放後の CANCELLED_CLEAN、retry 前 STARTED(N+1) readback、
旧 CPU 履歴維持と、旧 intent-only・公開不明・JPEG 入口後の HOLD を保つ。
共有候補は `ManualOriginalTrialSaveOwner`、`AndroidManualOriginalTrialSaves`、request/cursor、
storage file kind、cold-open UI。統合前に #365 の確定 head と codec/受付契約を照合する。

## 試験の境界

合成 JVM の観測試験と、intent/private/journal/insert/copy/close/publish/ack の process death 試験、
Android provider の実試験、実機 LMK は別々に記録する。読取専用基盤の試験だけで
要求 idempotency・永続救済・UI・#365 回帰の完了を宣言しない。
