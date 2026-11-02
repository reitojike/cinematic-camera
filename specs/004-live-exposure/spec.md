# Feature Specification: 即時露出と有限の応答待ち
**Feature Branch**: `fix/006-live-exposure`
**Created**: 2026-11-02
**Status**: ユーザー承認の仕様変更・修正対象
**Input**: Issue #37。適用の遅延と極端値で送信中停止。ISO/SSを即時・一般的な段階選択へ変更。

## User Scenarios & Testing
### User Story 1 - 標準段階で即時変更 (Priority: P1)
撮影者はISO/SSを選んだ時点で要求し、適用ボタンを押さずプレビュー反映を確認する。
**Why this priority**: 操作の遅延と任意値による扱いにくさを減らす。
**Independent Test**: 段階/合法値/pair/最新1件の自動検証。
**Acceptance Scenarios**:
1. **Given** AUTO実測または受付pair、**When** ISO/SS変更、**Then** 合法な標準候補でpairを作り即時要求、最初の送信は最大1表示frame程度に集約する。
2. **Given** 送信中、**When** 連続変更、**Then** 送信1件と最新待機1件のみ。古い受付で最新希望表示を上書きしない。
3. **Given** ISO1EV/SS1/3EV、**When** 表示、**Then** ISO100/200/400等、SS1/100・1/125・1/160等、長秒は0.3s・1s等。未対応候補は除外しrange端へ無断でclampしない。

### User Story 2 - 応答が戻らなくても復旧する (Priority: P1)
**Why this priority**: 無期限送信中はアプリを操作不能にする。
**Independent Test**: fake時計による期限・遅延応答・世代/背景化の検証。
**Acceptance Scenarios**:
1. **Given** API応答なし、**When** 前後の露光/実測frame周期を考慮した期限、**Then** 応答未確認/適用状態不明としbusy解除、待機破棄、古いnextも停止する。
2. **Given** 長露光、**When** 待機、**Then** 短い固定期限で失敗にせず低fpsと状態不明を区別する。
3. **Given** timeout/背景化/stop/rebind、**When** 遅い完了、**Then** 受付値/保存設定/画面を更新しない。AUTOまたは再接続で復旧できる。

### Edge Cases
候補0/1件、狭い範囲、非標準range端、reciprocalのns丸め、長→短変更、短→長変更、録画/保存中禁止、連打、失敗後queued値破棄。

## Requirements
### Functional Requirements
- **FR-001**: ISO/SSだけは003のpair適用式を上書きし即時選択。閉じる/取消で既に送信した設定を元へ戻すと表示しない。MFの適用式は維持。
- **FR-002**: 慣用名目値を整数ISO/nsへ対応させ、camera ISO/露光範囲・最大frame duration・CINE希望fpsのアプリ制約と交差する。
- **FR-003**: 片方編集時は最新待機→送信→受付→AUTO実測の順でpairを作る。AUTO起点は最寄り合法候補へ量子化、実測不明なら値を創作しない。
- **FR-004**: 操作tokenはbinding epochとは独立。期限/失敗後の古いclear→set→EVや受付/保存を拒否する。
- **FR-005**: 受付と実測を分ける。ハードウェア量子化による差を許容し、要求ns完全一致を完了条件にしない。
- **FR-007**: UNKNOWN/旧epoch/受付時刻不明では旧受付と同値でも再送する。既知の要求露光はcamera ID別のメモリ内期限hintとして再bindを越えて保持し、同cameraのclear/新露光受付後のみ短縮する。hintを実測/現在設定/保存設定として扱わない。
- **FR-006**: 露出AUTOによるWB/MF保持、全reset、録画中操作禁止、RAW原本/保存を維持する。
### Key Entities
標準段階、合法range、完全pair、送信中/最新待機、操作token/deadline、receiptとCaptureResult。

## Success Criteria
- **SC-001**: 自動検証で候補は合法範囲内、1/125は8,000,000nsとなる。
- **SC-002**: 100連続変更でもactiveは1件、待機は最新1件に制限される。
- **SC-003**: 応答なし・遅延完了・背景化でbusyや受付が永続的/誤った状態にならない。
- **SC-004**: build/unit/lint・最終head独立/canonical/CIを確認。実機原因と改善は再確認まで未検証。

## Assumptions
ISOは1EV、SSは慣用1/3EV、長秒は秒表記。範囲端に名目値が無い場合は候補なしの理由を示す。公称30秒やISO上限をPixel9aへ保証しない。
CINE frame周期制限はユーザー希望fpsを維持するためのアプリ方針で、AE_OFF時にAE_TARGET_FPS_RANGEが強制するという主張ではない。
ViewPort/設定overlayはIssue #38の別PR。実機報告・時系列はIssue、最終検証成果のみreportへ置く。
