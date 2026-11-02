# Feature Specification: 二段階露出simulationの固定pair検証
**Branch**: feat/009-exposure-simulation | **Date**: 2026-11-02 | **Status**: Draft
**Input**: Issue #42、明るさ優先/補償可能範囲内の滑らかさを二段階で検証するユーザー承認。

## User Scenarios & Testing
### US1 - 撮影設定を固定して二段階を試す (P1)
撮影者はSTILLの受付済みmanual pairで明示的に検証を開始し、撮影ISO/時間とpreview補償を区別する。
**Independent Test**: policy unitと公開API compile。実機ではintent別の要求/実測と画像を別に確認。
1. Given 有効なmanual pair、When opt-in、Then撮影pairを固定し、ISO余裕内の短露光または明るさ優先の長めpreviewを選択する。
2. Given 補償限界、When計算、Then固定1sで切り替えず、端末ISO/露光/frame範囲に基づき第2段階を選ぶ。無効/不明は開始しない。
3. Given 検証中、When画面確認、Then撮影target・preview target/受付・intent別実測・frame周期を分け、明るさ一致の実測合格としない。

### US2 - 安全に通常撮影へ戻る (P1)
**Independent Test**: state unit/static review、ユーザー実機で終了/撮影後/復帰を確認。
1. Given 検証中、When設定変更/終了、Then動的撮影pair更新を行わず、終了して通常sessionへ再bindする。補償値を通常preferencesへ保存しない。
2. Given 旧epoch/失敗/背景化、Whencallback、Then新状態へ実測/成功を書き込まず、検証を中断して通常復旧へ進める。
3. Given CINE、When動画撮影、Then検証の固定still設定やpreview補償を使わない。

### Edge Cases
初回bind長秒混入、actual ISO/SS量子化、boost非制御、previewより大きなstill streamの周期、RAW+JPEG二重callback、保存中終了、camera切替、回転、timeout、次frameが遅い復帰。

## Requirements
- **FR-001**: CameraX1.6.2公開APIの明示opt-in固定pair検証。既存通常経路/RAW原本/MediaStore保存を維持。
- **FR-002**: 二段階だけを定義。1はISO補償可能な短露光、2はISO限界で時間延長。固定秒数閾値なし。未知/無効な能力や不可能値は送信しない。
- **FR-003**: 撮影ISO/時間を保持。preview補償/共通postRAW boost要求と実測を別表示し、gain不明・noise/clipping/tone差は限界として示す。
- **FR-004**: ImageCapture固定optionsとruntime preview optionsを公開Interopで構成。初回bindにも長秒が混入し得ることを記録し、初期frameの安定を保証しない。
- **FR-005**: intent/epoch/frameを持つ観測で要求/受付/実測/保存を区別。still intentのframeをpreview実測へ誤表示しない。実fpsはsensor周期であり描画fps保証ではない。
- **FR-006**: 検証中の設定を固定し、preview補償値を通常設定へ保存しない。終了/背景化/失敗/旧callback/撮影中終了の境界を処理。
- **FR-007**: CINE移行時は検証を終了し通常bindへ戻す。動画制御や権限を増やさない。
- **FR-008**: boostは端末がwritableで100を許す場合のみ共通100を指定し、他は未制御と表示する。未制御/量子化を一致確認に変換しない。
- **FR-009**: 第3表示モード/露出計/GPU/NDK/Camera2直接制御/無断撮影/新署名設定は実装しない。範囲外の成立問題は根拠付きで報告する。

## Success Criteria
- **SC-001**: unitでstage境界/未知範囲/overflow/整数丸め/固定pair/旧epoch/終了/復帰を検証し、build/unit/lint/diff check成功。
- **SC-002**: 最終head独立/canonicalレビュー・必須CI・thread0を確認したDraft PR。merge/配布は別承認。
- **SC-003**: 実機のrepeat/単発actual、初回bind、fps、輝度/階調、撮影後復帰、RAW/動画回帰は未実施のまま限定手順を用意する。

## Assumptions
#41依存。検証開始時のmanual targetを固定し変更は終了後。preview目標周期は最小stream周期も考慮し、達成fps/見え方は実測する。普通の露出計は後続検討のみ。
