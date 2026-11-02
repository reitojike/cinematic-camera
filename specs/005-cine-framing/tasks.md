# Tasks: CINE画角一致と固定Preview

**Input**: specs/005-cine-framing/{spec,plan,research,data-model,contracts/ui,quickstart}.md
**Tests**: layout/input unit regressionと既存回帰を要求。実機ACは別管理。

## Phase 1: Setup

- [x] T001 Issue #38と003規則の置換をspecs/005-cine-framing/spec.mdへ定義する。

## Phase 2: Foundational

- [x] T002 公開API/geometry/input境界をspecs/005-cine-framing/plan.md、research.md、data-model.md、contracts/ui.mdへ定義する。

## Phase 3: US1 同じ画角で録画

Goal: CINEのquery/check/bindで同じViewPortを共有。Independent test: build/lintと許可後の四辺比較。

- [x] T003 [US1] app/src/main/java/dev/cinematic/p0/CineSession.ktでCINE専用9:16/ROTATION_0/FILL_CENTER ViewPortをsession query/check/bindへ共有し、Recorder16:9を指定する（FR-001）。
- [x] T004 [US1] app/src/main/java/dev/cinematic/p0/CameraProbe.ktでCINE Preview16:9優先とbound resolution/cropRect/rotationDegrees診断を追加し、STILL bindを維持する（FR-001/002/006）。
- [x] T005 [US1] app/src/test/java/dev/cinematic/p0/CaptureOrientationTest.ktとVideoPolicyTest.ktの既存回帰を実行しbusy出力回転固定/background停止を静的確認する（FR-005）。

## Phase 4: US2 固定Previewと設定overlay

Goal: 同じPreviewView/geometryで設定操作する。Independent test: layout/input/Back unitと許可後のUI操作。

- [x] T006 [P] [US2] app/src/test/java/dev/cinematic/p0/CaptureLayoutTest.ktへmode/panel別preview一定の回帰を追加する（FR-003）。
- [x] T007 [US2] app/src/main/java/dev/cinematic/p0/CaptureLayout.ktで96dp railのみ除外しoverlay高さをpreview内に制限する（FR-003）。
- [x] T008 [US2] app/src/main/java/dev/cinematic/p0/CaptureScreen.ktで設定をstable preview内overlayへ移動し、一定scale/inset、pointer/callback guard、Close/Back/stop semanticsを維持する（FR-003/004/005）。
- [x] T009 [P] [US2] app/src/main/java/dev/cinematic/p0/PreviewClickPolicy.ktとPreviewFocusGesture.ktでaccessibility中央/物理tap1回を区別し、app/src/test/java/dev/cinematic/p0/PreviewClickPolicyTest.ktで検証する（FR-004）。

## Phase 5: Validation

- [x] T010 build/unit/lint/diff check結果をdocs/BUILD_VALIDATION.mdへ記録する。specs/005-cine-framing/quickstart.mdの実機ACは未検証と分離する（SC-001/002、FR-006）。
- [x] T011 specs/005-cine-framing/tasks.mdへ機械検証と実機ACの完了境界を反映し、変更一覧/秘密除外を確認する。

## Dependencies / Parallel opportunities

T001→T002→US1(T003→T004→T005) / US2(T006→T007→T008、T009)→T010→T011。
US1とUS2 policy/testsは別ファイルで独立作業可能。MVPはUS1、今回US2まで実装する。

## 実機AC（未実施）

- [ ] T012 [US1] specs/005-cine-framing/quickstart.mdに従い、別途許可後にSDR/対応HDR・各quality/fps・4方向のpreview/MP4四辺一致とSTILL原本保持を確認する（SC-002）。
- [ ] T013 [US2] specs/005-cine-framing/quickstart.mdに従い、別途許可後に設定開閉・pointer/Back/TalkBack・録画中回転・background停止を確認する（SC-002）。

