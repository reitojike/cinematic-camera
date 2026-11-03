# Tasks: ライブ操作の最新意図と主値の可読性

**Input**: [spec.md](spec.md)、[plan.md](plan.md)
**Organization**: AC対応の完了状態だけを管理する。機械確認・実描画・実機受入・レビューを区別し、実機未確認を実装完了で代替しない。

既存Phase 1〜6の完了はそこで記載した実装/機械確認の範囲だけを示す。現行の[追加AC](https://github.com/reitojike/cinematic-camera/issues/47#issuecomment-6594720497)はPhase 7で追跡し、過去の完了チェックを今回のAUTO不具合解消や6キー実描画合格の証明にしない。T004の横scroll方針は現行FR-002の6キー同時表示へ置換する。

## Phase 1: Setup

- [x] T001 Issue #47/#37の範囲と旧004/009/012の置換を定め、標準resolver/setup-plan/setup-tasksでspec/plan/tasksを作る（specs/013-live-control-intent/spec.md、plan.md、tasks.md、specs/004-live-exposure/spec.md、specs/009-capture-ui-states/spec.md、specs/012-v15-capture-feedback/spec.md）。FR-007。

## Phase 2: Foundational

- [x] T002 最新logical ControlSnapshotを単一authorityにし、flight1+pending1/32ms、ACK後latest、失敗/timeout cancelとintent保持、reset/rebind・epoch/background無効化/復帰を実装する。旧exposure queueと重要テストを置換する（app/src/main/java/dev/cinematic/p0/LiveControlQueue.kt、CameraProbe.kt、CaptureState.kt、app/src/test/java/dev/cinematic/p0/LiveControlQueueTest.kt）。FR-004/006、SC-003。

## Phase 3: User Story 1 - AUTO主値と可読性 (Priority: P1 / MVP)

**Goal / Independent Test**: 正常更新中の不要空白をなくし、縦横・文字倍率・主値端点で欠落を確認する。

- [x] T003 [US1] diagnostics throttle前で軸別正常実測を集約し、元timestamp/epochと2000ms TTLを保ち、無効/旧/逆転観測で延命しない（app/src/main/java/dev/cinematic/p0/CameraProbe.kt、CaptureState.kt、CaptureScreen.kt）。FR-001。
- [x] T004 [US1] 主数値/単位を近似説明から分離し、実測TextMeasurer/minWidthと狭幅rail横scroll/overlay高さ適応で数値省略を防ぐ。preview寸法を維持する（app/src/main/java/dev/cinematic/p0/ExposureValuePresentation.kt、ExposureControls.kt、CaptureChrome.kt、CaptureChromeModel.kt、CaptureScreen.kt）。FR-002。
- [x] T005 [US1] 正常更新→欠落混在、片軸/不正/用途/旧epoch/未来・逆転/2000ms境界、MANUAL/RAW target非書戻しと最長/最短値の表示を機械確認する（app/src/test/java/dev/cinematic/p0/PreviewExposureSamplesTest.kt、ExposureValuePresentationTest.kt、ControlScaleTest.kt、CaptureChromeRegressionTest.kt）。FR-001/002、SC-001。
- [ ] T006 [US1] 縦/左右横×fontScale 1.0/1.3/2.0×最長/最短主値でLOOKを含む6キー同時表示の実描画と、AUTO正常session中のちらつき・更新中のISO/SS押下/drag/目盛り操作を実機確認する。検証viewport・文字サイズ下限と未確認条件を明示し、未確認を合格にせずIssue #47へ結果要約を記録する（最終結果の必要時反映先docs/P0_REPORT.md）。SC-001/004/005。

## Phase 4: User Story 2 - EV/WB連続intent (Priority: P1)

**Goal / Independent Test**: EV 0→+1/WB往復でpending中の次段と遅い中間ACK後の最新値を確認する。

- [x] T007 [US2] EV/WBの選択投影をlatest intentへ接続しpending UI guardを除く。drag/tap/矢印/a11yと段変更のみhaptic、能力検査・MANUAL EV制約を維持する（app/src/main/java/dev/cinematic/p0/SensorControlEditors.kt、CaptureScreen.kt、CapturePanels.kt、ExposureControls.kt、SensorControlPresentation.kt、CameraProbe.kt）。FR-003/004。
- [x] T008 [US2] EV 0→+1/WB連続・往復、pending中次段、遅い中間ACK、200操作の有限coalescing、ISO/SS補償との同時変更・失敗/timeout/復帰を機械確認する（app/src/test/java/dev/cinematic/p0/LiveControlQueueTest.kt、ProbeControlsTest.kt、SensorControlPresentationTest.kt、ControlScaleTest.kt）。FR-003/004/006、SC-002/003。
- [ ] T009 [US2] EV/WBの応答待ちを挟まない連続選択と操作中反映を実機確認し、選択/要求/受付/実測を区別してIssue #47へ要約する（最終結果の必要時反映先docs/P0_REPORT.md）。SC-002/003/004。

## Phase 5: User Story 3 - MF即時preview (Priority: P1)

**Goal / Independent Test**: MF往復drag中の要求継続と適用操作なしの探索、AF/MF/∞・復帰を確認する。

- [x] T010 [US3] MFを即時queue選択へ接続し適用/取消UIを除く。epoch/maxごとのstable exact anchor、100等分候補、AF/MF/∞と不正/非対応guardを維持する（app/src/main/java/dev/cinematic/p0/SensorControlEditors.kt、CaptureScreen.kt、CapturePanels.kt、SensorControlPresentation.kt、CameraProbe.kt）。FR-003/005/006。
- [x] T011 [US3] MF往復drag中の複数要求・指離し前dispatch、pending次段と中間ACK巻戻り防止、exact anchor/丸め非書戻し、AF/MF/∞・能力変更・epoch/background/失敗復帰を機械確認する（app/src/test/java/dev/cinematic/p0/LiveControlQueueTest.kt、SensorControlPresentationTest.kt、ProbeControlsTest.kt、ControlScaleTest.kt）。FR-003/004/005/006、SC-002/003。
- [ ] T012 [US3] MF往復を適用操作0回で探索し、操作中preview変化・AF/MF/∞・背景復帰・同時ISO/SS変更を実機確認してIssue #47へ要約する（最終結果の必要時反映先docs/P0_REPORT.md）。SC-002/003/004。

## Phase 6: Polish & Cross-Cutting Concerns

- [x] T013 spec/plan/tasksのread-only analyzeと実装後convergeを行い、通常unit/lint/assemble・diffcheck・公開情報検査を確認する。保証を機械/実描画/実機へ分ける（specs/013-live-control-intent/tasks.md、docs/BUILD_VALIDATION.md、docs/PUBLICATION_REVIEW.md、.github/workflows/android.yml）。FR-007、SC-004。
- [ ] T014 最新head必須CI・独立明示OK・canonical review・未解決thread0とbaseを確認してDraft PRで提示し、merge/配布は別承認まで停止する（docs/OPERATIONS.md）。FR-007、SC-004。

## Phase 7: AUTO更新中の入力・6キー補正

**Goal / Independent Test**: US1/AC4〜5と更新したAC3を確認する。正常観測更新と入力の交差、真の鮮度不足/epoch変更を分け、6キーはスクロールなしで同時に確認する。

- [ ] T015 [US1] AUTO表示のclock/sample更新順を照合し、正常stream更新中の不要空白・候補消失を防ぐ。元timestamp/epoch/2000ms TTL、軸別集約と不正/旧/未来sample拒否を維持し、stream境界を回帰確認する（app/src/main/java/dev/cinematic/p0/CaptureScreen.kt、ExposureValuePresentation.kt、LiveControlQueue.kt、app/src/test/java/dev/cinematic/p0/PreviewExposureSamplesTest.kt、ExposureValuePresentationTest.kt、LiveControlQueueTest.kt）。FR-001/008、SC-001/005。
- [ ] T016 [US1] ISO/SSの目盛り/gesture identityと入力readinessを表示更新から分離し、正常AUTO stream中の押下・drag/tap/矢印/a11yとMANUAL最新intent移行を回帰確認する。元実測pair・能力/epochの安全境界を維持する（app/src/main/java/dev/cinematic/p0/CaptureScreen.kt、ExposureControls.kt、ControlScale.kt、CameraProbe.kt、LiveControlQueue.kt、app/src/test/java/dev/cinematic/p0/ControlScaleTest.kt、LiveControlQueueTest.kt、AutoExposureInputTest.kt）。FR-003/004/008、SC-002/005。
- [ ] T017 [US1] ISO/SS/EV/WB/FOCUS/LOOKを共通cellで6キー同時表示し、rail横scrollを除く。縦/左右横・最長/最短値・fontScale 1.0/1.3/2.0の測定回帰を行い、文字サイズ下限と検証viewportを設計へ反映する。preview寸法/操作領域を維持する（app/src/main/java/dev/cinematic/p0/ExposureControls.kt、CaptureScreen.kt、ControlScale.kt、app/src/test/java/dev/cinematic/p0/ControlScaleTest.kt、CaptureChromeRegressionTest.kt、specs/013-live-control-intent/plan.md）。FR-002、SC-001/005。
- [ ] T018 追加差分のread-only analyze・実装後converge、unit/lint/assemble・diffcheck・公開情報検査とEV/WB/MFの既存回帰を確認する。実描画/実機未確認はT006へ、remote gateはT014へ残す（specs/013-live-control-intent/tasks.md、.github/workflows/android.yml、docs/BUILD_VALIDATION.md、docs/PUBLICATION_REVIEW.md）。FR-007、SC-004/005。

## Dependencies & Execution Order

T001→T002→US2/US3の実装→各機械確認。US1はT001後に独立着手でき、T003→T004→T005。各実機AC（T006/T009/T012）は対応機械確認後に別判定する。T013は各機械確認後、T014は最終head固定後。共有CameraProbe/SensorControlEditors/CapturePanelsの編集は直列化し、別担当の同一ファイルを上書きしない。

追加補正はT015→T016、T017は独立した配置測定の設計/テストを並行可能とする。CaptureScreen/ExposureControls等の共有編集は調整して直列化する。T015〜017後にT018、実機受入はT006、現行headのremote gateはT014で判定する。既存完了のT013を追加差分の検証済みに読み替えない。

## Parallel Examples / Implementation Strategy

US1の観測集約/表示とUS2/US3共通queue設計は別担当で読取・テスト設計を並行できる。US2はEV/WBシナリオ、US3はMF exact anchor/往復シナリオを独立設計し、共有sourceへの反映は直列化する。まずUS1を独立に検証し、単一queueの後EV/WB→MFを統合する。機械確認後も実機ACは未完了のまま保持し、Issue/PRコメントへ日々の状況を置く。
