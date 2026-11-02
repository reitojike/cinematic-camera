# Tasks: 撮影UIの状態・制御値・診断整理

**Input**: [spec.md](spec.md)、[plan.md](plan.md)
**Tests**: Issue #47のpure表示policy/状態回帰要求に対応する。実描画/実機合格を機械検証と分離する。

## Phase 1: Setup

- [x] T001 Issue #47・#45監査とconstitutionを照合し、標準template resolver/setup-plan/setup-tasksで仕様・計画・ACを作成する（specs/009-capture-ui-states/spec.md、specs/009-capture-ui-states/plan.md）。FR-007/008/009。

## Phase 2: Foundational

- [x] T002 構造化stateだけを受け取る最小純粋表示policyを定義し、shape/tone/action/accessibility/enabled/reason、共通表示先、項目別変更/鮮度、情報源、欠落値を分離する（app/src/main/java/dev/cinematic/p0/CaptureChromeModel.kt、app/src/main/java/dev/cinematic/p0/CaptureFeedback.kt、app/src/main/java/dev/cinematic/p0/SensorControlPresentation.kt）。FR-001/002/003/004/005/009。

## Phase 3: User Story 1 - シャッター・状態・無効理由 (P1)

**Goal**: 全撮影/録画/保存stageを識別し停止・復旧へ到達できる。
**Independent Test**: 全stageとbusy/失敗/権限/非対応/熱の純粋入力表を検証し、既存actionへの接続を照合する。

- [x] T003 [US1] STILLの全PhotoStage、CINEの全RecordingStageと独立した保存結果/失敗、busy/非対応/権限/熱でSTILL大白/CINE小白・STARTING白/RECORDING赤、caption非描画/停止到達性をテストする（app/src/test/java/dev/cinematic/p0/CaptureChromeModelTest.kt、app/src/test/java/dev/cinematic/p0/CaptureFeedbackTest.kt、app/src/test/java/dev/cinematic/p0/SensorControlPresentationTest.kt、app/src/test/java/dev/cinematic/p0/CaptureChromeRegressionTest.kt）。FR-001/004、SC-001。
- [x] T004 [US1] シャッターの内円/四角・最新PO色を接続し、下captionを完全非描画にして進捗は状態バー/失敗理由はnotice/accessibilityへ移し、STARTING/RECORDINGの既存停止actionと復旧/Backを維持する（app/src/main/java/dev/cinematic/p0/CaptureScreen.kt、app/src/main/java/dev/cinematic/p0/CaptureChrome.kt）。FR-001/004/006。
- [x] T005 [US1] camera ID/撮影形式/video quality・fps/録画・保存・熱のchipを構造化情報から表示し、選択/bindと実測/実出力を区別、未知は「—」等とし、露出modeと影響項目送信状態を共通領域各一か所へ集約する（app/src/main/java/dev/cinematic/p0/CaptureScreen.kt、app/src/test/java/dev/cinematic/p0/CaptureChromeModelTest.kt、app/src/test/java/dev/cinematic/p0/CaptureFeedbackTest.kt、app/src/test/java/dev/cinematic/p0/SensorControlPresentationTest.kt）。FR-002/004、SC-002。

## Phase 4: User Story 2 - MF/WBの読み取り (P2)

**Goal**: 値・単位・tagと要求/受付を読み分け、従来の操作を保つ。
**Independent Test**: AF/MF、長い小数、draft/受付/不明、WB選択/送信/拒否をpolicyと既存editor回帰で検証する。

- [x] T006 [US2] MF表示丸め/LU/AF・MF tagとWB選択を実装し、pending/不明は変更した項目に限り無関係なMF/WBを全snapshot送信中にせず、表示丸めを送信値へ逆流させず、既存MF適用/取消・WB即時送信を維持する（app/src/main/java/dev/cinematic/p0/CaptureChromeModel.kt、app/src/main/java/dev/cinematic/p0/CaptureFeedback.kt、app/src/main/java/dev/cinematic/p0/SensorControlPresentation.kt、app/src/main/java/dev/cinematic/p0/SensorControlEditors.kt、app/src/main/java/dev/cinematic/p0/CaptureScreen.kt）。FR-003/006。
- [x] T007 [US2] 項目別logical target/override変更だけへのpending/unknownと、draft/要求/受付履歴/実測の混同、WB旧受付を現在成功にする誤表示、未知値の捏造を防ぐunitと既存editor回帰を確認する（app/src/test/java/dev/cinematic/p0/CaptureChromeModelTest.kt、app/src/test/java/dev/cinematic/p0/CaptureFeedbackTest.kt、app/src/test/java/dev/cinematic/p0/SensorControlPresentationTest.kt、app/src/test/java/dev/cinematic/p0/CaptureStateTest.kt）。FR-003、SC-002/003。

## Phase 5: User Story 3 - 設定・診断の整理 (P2)

**Goal**: 設定target/要求/受付履歴/実測/保存を独立して確認できる。
**Independent Test**: 見出しと情報源・既存report/復旧/Back入口を静的照合し、固定layout/rotation/editorの既存回帰を確認する。

- [x] T008 [US3] 設定を選択操作、診断をtarget/要求/受付履歴/preview補償/実測/保存の詳細比較へ整理し、settings内詳細列・横持ち多段説明・キー/目盛りの重複説明を除去して既存レポート保存/復旧/Backを維持する（app/src/main/java/dev/cinematic/p0/CaptureScreen.kt、app/src/main/java/dev/cinematic/p0/CapturePanels.kt）。FR-005/006、SC-002。
- [x] T009 [US3] 縦/左右横持ちで要素回転・固定配置/overlayを保ち、ISO/SS即時・反映ボタンなし、AUTO/reset分離、録画継続/RAW保存への既存callsite不変を静的確認し、layout/orientation/editor unitを回帰確認する（app/src/main/java/dev/cinematic/p0/CaptureScreen.kt、app/src/test/java/dev/cinematic/p0/CaptureLayoutTest.kt、app/src/test/java/dev/cinematic/p0/CaptureOrientationTest.kt、app/src/test/java/dev/cinematic/p0/CaptureStateTest.kt）。FR-006/007、SC-003。

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T010 pure表示policy・既存unit・build/lint・diffcheck・公開scanを実行し、参照構造/CSS照合と未実施の実描画/実機を分離して記録する（app/src/test/java/dev/cinematic/p0/CaptureChromeModelTest.kt、app/src/test/java/dev/cinematic/p0/CaptureFeedbackTest.kt、app/src/test/java/dev/cinematic/p0/SensorControlPresentationTest.kt、docs/BUILD_VALIDATION.md、docs/PUBLICATION_REVIEW.md）。FR-008/009、SC-001/002/003/004。
- [ ] T011 Draft PRの最終headに情報配置/不要重複の独立点検・独立/canonicalレビュー・必須CI成功・未解決thread 0を確認し、merge/配布前で停止する（docs/OPERATIONS.md、specs/009-capture-ui-states/tasks.md）。SC-004。
- [ ] T012 縦/左右横持ちの可読性/重なり、停止・保存・失敗・復旧/Back、MF/WB、近似previewとtargetの区別、2000ms失効、detent/haptic設定尊重、overlay、回転録画継続、RAW保存をユーザー確認後に記録する（docs/P0_REPORT.md）。FR-006/008、SC-003。browser拒否の迂回・再試行を行わず、HTML実描画と実機未確認を合格にしない。

## Dependencies & Execution Order

T001→T002→US1 T003–T005→US2 T006–T007→US3 T008–T009およびT013/T014→T010→T011。T012は実機確認後のみ。各storyの表示policyは独立検証できるが、CaptureScreenへの統合は同じ所有者が逐次行う。

## Parallel Examples

US1のT003状態表テスト設計と参照構造照合、US2のT007既存editor回帰確認とMF/WBの表示検討、US3のT009既存layout/orientation確認とセクション構造照合は並行可能。同一CaptureScreen/表示policyファイルの編集競合を避けるため[P]は付けない。

## Implementation Strategy

先に純粋表示policyと状態表を固め、シャッター/状態バー、MF/WB、設定/診断の順で既存画面へ接続する。小規模unitと標準build/lint、静的callsite照合で機械側を検証し、Draft PRへ進める。HTML実描画/実機は未確認のまま区別し、merge/配布は別承認とする。

## Phase 7: 最新POの主値・操作感

- [x] T013 [US2] ISO/SS主値を現epoch/preview系intent（PREVIEW・VIDEO_RECORDのみ）/取得後2000ms以内の実測から標準候補へdisplay-only丸めして≈表示し、欠落/旧/期限超過を—へ戻す。目盛りは独立capturetarget選択のみ、modeは共通領域一回にして同じ数値/API説明を重ねない（app/src/main/java/dev/cinematic/p0/ExposureValuePresentation.kt、app/src/main/java/dev/cinematic/p0/ExposureControls.kt、app/src/main/java/dev/cinematic/p0/CaptureScreen.kt、app/src/test/java/dev/cinematic/p0/ExposureValuePresentationTest.kt）。FR-002/010、SC-002。鮮度境界・補償preview/target分離・camera/候補書戻しなしをpure境界とcallsiteで確認する。
- [x] T014 [US2] drag距離を蓄積するdetentを実装し、実index変更時だけ共通select actionと標準軽haptic（flags 0）へ接続する。端点/同値/反転とtap/矢印/accessibility経路を回帰確認し、新権限/振動engineを追加しない（app/src/main/java/dev/cinematic/p0/ControlScale.kt、app/src/main/java/dev/cinematic/p0/ExposureControls.kt、app/src/test/java/dev/cinematic/p0/ControlScaleTest.kt）。FR-009/011、SC-002/003。
