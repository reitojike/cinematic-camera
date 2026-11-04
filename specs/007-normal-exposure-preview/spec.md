# Feature Specification: 通常STILLの二段階露出プレビュー自動追従

**Feature Branch**: `fix/010-normal-exposure-preview`
**Created**: 2026-11-02
**Status**: Draft
**Input**: [Issue #44](https://github.com/reitojike/cinematic-camera/issues/44)。通常STILL MANUALのISO/SS即時変更へ二段階previewを自動追従させる。

## User Scenarios & Testing

### User Story 1 - 通常操作で明るさを確認する (Priority: P1)

撮影者は通常STILL MANUALのISO/SSを変更し、明示的なPoC開始なしで撮影設定に近い明るさのpreviewを確認する。
**Why this priority**: 数秒の撮影SSを待機previewへ流したときの操作応答低下を改善する中心機能。
**Independent Test**: 二段階境界と連続編集を状態遷移テストし、修正版実機ではISO余裕内/上限到達時の明るさと応答を確認する。

**Acceptance Scenarios**:

1. **Given** 有効な端末能力とSTILL MANUAL、**When** ISO/SSを変更する、**Then** 撮影targetを直ちに更新し、ISO補償可能範囲では短露光、補償上限では明るさ優先の長め露光をpreview targetとする。
2. **Given** preview設定が処理中、**When** 複数回編集する、**Then** 最新要求を優先し、中間値を順番に消化するqueueを積み上げず、旧要求の完了で最新表示を巻き戻さない。
3. **Given** 初期起動または再接続、**When** 長秒の撮影targetを復元する、**Then** 待機previewへ撮影専用の長秒pairを混入させない。第2段階として正当に選んだ長めpreviewは許容する。
4. **Given** 長秒設定、**When** 短秒またはAUTOへ戻す、**Then** 最新設定へ復帰し、古い補償設定を残さない。センサー処理中の不可避待ちは明示する。

### User Story 2 - 選んだ露出で単発撮影しpreviewへ戻る (Priority: P1)

撮影者はシャッター時点の撮影targetで撮影し、保存完了後は最新の通常previewへ戻る。
**Why this priority**: previewの応答改善で静止画の撮影露出やRAW保存を変えないため。
**Independent Test**: 撮影snapshot、撮影設定受付、全出力のterminal、復帰を模擬し、保存callback順の違いとtimeoutを検証する。

**Acceptance Scenarios**:

1. **Given** 補償preview中、**When** シャッターを押す、**Then** 撮影targetの世代とISO/SSを固定し、その撮影設定の受付後に撮影する。補償previewのpairを撮影targetとして保存しない。
2. **Given** JPEG/RAWを含む撮影が進行中、**When** 一方だけ完了する、**Then** もう一方がterminalになるまで撮影の専有状態を保持し、全出力のterminal後に最新previewへ復帰する。
3. **Given** 撮影または受付のtimeout/失敗、**When** 結果が不明になる、**Then** 成功や復帰完了と表示せず、実行中撮影の結果確定または明示的な停止による世代切断まで競合する設定変更を許さない。

### User Story 3 - 状態と待ち理由を正しく理解する (Priority: P2)

撮影者は撮影target、preview target、設定受付、センサー実測を区別し、待ちが必要な理由を把握する。
**Why this priority**: 受付やビルド成功を実機の露出・画質一致と取り違えないため。
**Independent Test**: 旧世代callback、背景復帰、失敗、撮影intentの実測を投入し、表示と状態が混ざらないことを確認する。

**Acceptance Scenarios**:

1. **Given** 要求または受付のみ、**When** 状態を見る、**Then** その値を実測一致/明るさ一致と表示せず、撮影targetをpreview受付値と呼ばない。
2. **Given** 設定反映/撮影/復帰待ち、**When** 待ち理由を見る、**Then** センサーの不可避露光待ち、設定受付待ち、アプリ処理queue/再接続待ちを区別する。
3. **Given** 背景化/カメラ切替/再接続、**When** 旧callbackが到着する、**Then** 現在の要求/受付/実測/保存成功へ書き込まず、復帰は現世代の最新設定から行う。

### Edge Cases

未知/不正な能力、ISO上限と整数丸め、実測の量子化、boost未制御、初期MANUAL復元、長秒から短秒/AUTO、連打、設定受付失敗、撮影前受付timeout、takePicture後timeout、RAW+JPEGの順不同/二重callback、背景化と復帰、CINE遷移、旧epochの遅延完了。

## Requirements

### Functional Requirements

- **FR-001**: 通常STILL MANUALのISO/SS即時変更から自動的に二段階previewを選び、明示PoC開始を不要にする。
- **FR-002**: ISO補償内の短露光と補償上限で明るさを優先する長め露光だけを使う。固定1秒閾値、第3描画mode、無関係なAUTO previewへの置換を使わない。
- **FR-003**: 撮影target/preview target/受付/実測を別管理し、要求・session・撮影の世代に属さない旧callbackを採用しない。連続編集はlatest-winsとする。
- **FR-004**: 初期/再接続時に撮影専用の長秒pairを待機previewへ混入させず、通常編集ごとの不要な全再接続を行わない。AUTO/全reset時は古い固定設定も除去する。
- **FR-005**: シャッター時の撮影snapshotを固定し、撮影設定受付→撮影→全出力terminal→最新preview復帰の順序を守る。撮影中は既存busy状態で競合操作を防ぐ。
- **FR-006**: timeoutだけで未完了撮影をキャンセル済みとせず、結果不明を表示し、terminalまたは明示停止の世代切断まで競合preview上書きを禁止する。
- **FR-007**: 短秒/AUTO/背景復帰/失敗で最新要求へ戻し、不可避センサー待ちとアプリ由来のqueue/再接続遅延を区別する。物理待ちを隠さず、受付のみで実測・画質一致としない。
- **FR-008**: 現行overlay、即時設定反映、CINE、RAW原本、標準MediaStore保存を維持する。preview補償値を通常撮影設定へ保存しない。
- **FR-009**: 能力範囲外/未知のpairを送信せず、非対応・gain未制御・量子化・noise/clipping/tone差を限界として扱う。
- **FR-010**: 公開標準APIの制約で成立しない場合は根拠と最小代案を報告する。GPU/NDK/Camera2直接制御/新権限/露出計/署名鍵Secretsへ拡大しない。

### Key Entities

- **撮影target**: 通常ISO/SS、改訂番号。シャッター時に固定され、preview補償から独立する。
- **preview要求・受付**: 二段階policyの結果、改訂番号、session世代、要求/受付状態。
- **実測**: session世代、frame/intent、ISO/露光/周期/boost。撮影とpreviewを区別する。
- **撮影処理**: 固定snapshot、受付・撮影・復帰phase、出力ごとのterminal、結果不明状態。

## Success Criteria

### Measurable Outcomes

- **SC-001**: 連続編集、二段階境界、旧世代、撮影/復帰/AUTO/背景化/失敗の全定義シナリオで、最終要求の巻き戻しと撮影/preview混同が0件となる。
- **SC-002**: 初期/再接続へ撮影専用long pairを含む経路と、通常編集1回ごとに全再接続する経路が0件となる。
- **SC-003**: 修正版向け限定実機手順で二段階の見え方、長秒→短秒/AUTO、撮影後復帰、RAW/CINE回帰を確認可能とする。実機結果を得るまで改善・全回帰合格を未確認に保つ。
- **SC-004**: Draft PRの最終headに必要な機械検証、独立/canonicalレビュー、必須CI、未解決thread 0件の根拠を揃える。merge/配布は個別承認まで実施しない。

## Assumptions

- #42固定pair PoCはユーザーの定性的確認を得て完了したが、T009を含む全実機証明ではない。通常経路の#37は包括解決済みにしない。
- v13で追加実機確認を要求せず、修正版でまとめて確認する。端末固有の応答秒数/輝度一致は証拠なしに保証しない。
- 操作部のHTML準拠は#45の別スコープであり、本変更で即時反映/overlayを後退させない。
