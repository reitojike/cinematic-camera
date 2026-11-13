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

## 永続化・既存 publisher への接続（未実装）

旧 intent / journal は保持する。照合記録には旧 journal 全体またはその厳密な識別と revision、
観測 URI と byte 証拠を固定し、旧 UNKNOWN を通常状態へ書き換えない。
同一 pending 行の再開は既存 `RecoverablePhotoPublisher` の経路を再利用し、再描画・新規 insert をしない。
公開前の原本再照合を省略しない。公開済みの後日削除・改変から自動再作成しない。
書きかけ削除や既存 JPEG 上書きは暗黙に追加しない。

## 明示的な別書き出し（未実装）

初期状態は保留。「もう一度確認」「今は保存しない」「重複の可能性はあるが保存し直す」を区別する。
最後の選択だけで、旧要求との関連を持つ独立した永続要求を作る。旧保存を成功／取消に偽装しない。
原本 URI 複製や source cursor の黙った上書きで identity を迂回しない。
固定 idempotency key の commit/readback、二重タップ・kill・ack 喪失後の再取得、原本 hash 再照合、
旧新 owner の排他、再び不明になった要求の保留が接続前の条件。

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
