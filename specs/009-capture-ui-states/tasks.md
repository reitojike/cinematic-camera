# Tasks: 撮影UIの状態・制御値・診断整理

**Input**: [spec.md](spec.md)、[plan.md](plan.md)
**Tests**: Issue #47のpure表示policy/状態回帰要求に対応する。実描画/実機合格を機械検証と分離する。

## Phase 1: Setup

- [x] T001 Issue #47・#45監査とconstitutionを照合し、標準template resolver/setup-plan/setup-tasksで仕様・計画・ACを作成する（specs/009-capture-ui-states/spec.md、specs/009-capture-ui-states/plan.md）。FR-007/008/009。

## Phase 2: Foundational

- [x] T002 構造化stateだけを受け取る最小純粋表示policyを定義し、shape/tone/caption/action/enabled/reason、情報源、欠落値を分離する（app/src/main/java/dev/cinematic/p0/CaptureChromeModel.kt、app/src/main/java/dev/cinematic/p0/CaptureFeedback.kt、app/src/main/java/dev/cinematic/p0/SensorControlPresentation.kt）。FR-001/002/003/004/005/009。

## Phase 3: User Story 1 - シャッター・状態・無効理由 (P1)

**Goal**: 全撮影/録画/保存stageを識別し停止・復旧へ到達できる。
**Independent Test**: 全stageとbusy/失敗/権限/非対応/熱の純粋入力表を検証し、既存actionへの接続を照合する。

- [x] T003 [US1] STILLの全PhotoStage、CINEの全RecordingStageと独立した保存結果/失敗、busy/非対応/権限/熱でshape/tone/caption/停止到達性と固定領域内の四半回転caption配置をテストする（app/src/test/java/dev/cinematic/p0/CaptureChromeModelTest.kt、app/src/test/java/dev/cinematic/p0/CaptureFeedbackTest.kt、app/src/test/java/dev/cinematic/p0/SensorControlPresentationTest.kt、app/src/test/java/dev/cinematic/p0/CaptureChromeRegressionTest.kt）。FR-001/004、SC-001。
- [x] T004 [US1] シャッターの内円/四角・状態色・固定captionを表示policyへ接続し、STARTING/RECORDINGの既存停止actionと復旧/Backを維持する（app/src/main/java/dev/cinematic/p0/CaptureScreen.kt、app/src/main/java/dev/cinematic/p0/CaptureChrome.kt、app/src/main/java/dev/cinematic/p0/ShutterCaptionLayout.kt）。FR-001/004/006。
- [x] T005 [US1] camera ID/撮影形式/video quality・fps/録画・保存・熱のchipを構造化情報から表示し、選択/bindと実測/実出力を区別、未知は「—」等とする（app/src/main/java/dev/cinematic/p0/CaptureScreen.kt、app/src/test/java/dev/cinematic/p0/CaptureChromeModelTest.kt、app/src/test/java/dev/cinematic/p0/CaptureFeedbackTest.kt、app/src/test/java/dev/cinematic/p0/SensorControlPresentationTest.kt）。FR-002/004、SC-002。

## Phase 4: User Story 2 - MF/WBの読み取り (P2)

**Goal**: 値・単位・tagと要求/受付を読み分け、従来の操作を保つ。
**Independent Test**: AF/MF、長い小数、draft/受付/不明、WB選択/送信/拒否をpolicyと既存editor回帰で検証する。

- [x] T006 [US2] MF表示丸め/LU/AF・MF tagとWB現在選択/受付状態を実装し、表示丸めを送信値へ逆流させず、既存MF適用/取消・WB即時送信を維持する（app/src/main/java/dev/cinematic/p0/CaptureChromeModel.kt、app/src/main/java/dev/cinematic/p0/CaptureFeedback.kt、app/src/main/java/dev/cinematic/p0/SensorControlPresentation.kt、app/src/main/java/dev/cinematic/p0/SensorControlEditors.kt、app/src/main/java/dev/cinematic/p0/CaptureScreen.kt）。FR-003/006。
- [x] T007 [US2] draft/要求/受付履歴/実測の混同、WB旧受付を現在成功にする誤表示、未知値の捏造を防ぐunitと既存editor回帰を確認する（app/src/test/java/dev/cinematic/p0/CaptureChromeModelTest.kt、app/src/test/java/dev/cinematic/p0/CaptureFeedbackTest.kt、app/src/test/java/dev/cinematic/p0/SensorControlPresentationTest.kt、app/src/test/java/dev/cinematic/p0/CaptureStateTest.kt）。FR-003、SC-002/003。

## Phase 5: User Story 3 - 設定・診断の整理 (P2)

**Goal**: 設定target/要求/受付履歴/実測/保存を独立して確認できる。
**Independent Test**: 見出しと情報源・既存report/復旧/Back入口を静的照合し、固定layout/rotation/editorの既存回帰を確認する。

- [x] T008 [US3] 設定/診断をセクション整理し、target/要求/受付履歴/実測/保存を明示して既存レポート保存/復旧/Backを維持する（app/src/main/java/dev/cinematic/p0/CaptureScreen.kt、app/src/main/java/dev/cinematic/p0/CapturePanels.kt）。FR-005/006、SC-002。
- [x] T009 [US3] 縦/左右横持ちで要素回転・固定配置/overlayを保ち、ISO/SS即時・反映ボタンなし、AUTO/reset分離、録画継続/RAW保存への既存callsite不変を静的確認し、layout/orientation/editor unitを回帰確認する（app/src/main/java/dev/cinematic/p0/CaptureScreen.kt、app/src/test/java/dev/cinematic/p0/CaptureLayoutTest.kt、app/src/test/java/dev/cinematic/p0/CaptureOrientationTest.kt、app/src/test/java/dev/cinematic/p0/CaptureStateTest.kt）。FR-006/007、SC-003。

## Phase 6: Polish & Cross-Cutting Concerns

- [x] T010 pure表示policy・既存unit・build/lint・diffcheck・公開scanを実行し、参照構造/CSS照合と未実施の実描画/実機を分離して記録する（app/src/test/java/dev/cinematic/p0/CaptureChromeModelTest.kt、app/src/test/java/dev/cinematic/p0/CaptureFeedbackTest.kt、app/src/test/java/dev/cinematic/p0/SensorControlPresentationTest.kt、docs/BUILD_VALIDATION.md、docs/PUBLICATION_REVIEW.md）。FR-008/009、SC-001/002/003/004。
- [ ] T011 Draft PRの最終headに独立/canonicalレビュー・必須CI成功・未解決thread 0を確認し、merge/配布前で停止する（docs/OPERATIONS.md、specs/009-capture-ui-states/tasks.md）。SC-004。
- [ ] T012 縦/左右横持ちの可読性/重なり、停止・保存・失敗・復旧/Back、MF/WB、overlay、回転録画継続、RAW保存をユーザー確認後に記録する（docs/P0_REPORT.md）。FR-006/008、SC-003。browser拒否の迂回・再試行を行わず、HTML実描画と実機未確認を合格にしない。

## Dependencies & Execution Order

T001→T002→US1 T003–T005→US2 T006–T007→US3 T008–T009→T010→T011。T012は実機確認後のみ。各storyの表示policyは独立検証できるが、CaptureScreenへの統合は同じ所有者が逐次行う。

## Parallel Examples

US1のT003状態表テスト設計と参照構造照合、US2のT007既存editor回帰確認とMF/WBの表示検討、US3のT009既存layout/orientation確認とセクション構造照合は並行可能。同一CaptureScreen/表示policyファイルの編集競合を避けるため[P]は付けない。

## Implementation Strategy

先に純粋表示policyと状態表を固め、シャッター/状態バー、MF/WB、設定/診断の順で既存画面へ接続する。小規模unitと標準build/lint、静的callsite照合で機械側を検証し、Draft PRへ進める。HTML実描画/実機は未確認のまま区別し、merge/配布は別承認とする。
