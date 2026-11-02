# Tasks: 撮影コントロールの選択スケール

**Input**: [spec.md](spec.md), [plan.md](plan.md)

## Phase 1: Setup

- [x] T001 採用稿と現行差分・対象外を specs/008-control-scale-ui/spec.md と plan.md に定義する。

## Phase 2: Foundational

- [x] T002 中央5候補と端/空/単一候補のモデルを app/src/main/java/dev/cinematic/p0/ControlScale.kt に実装する。

## Phase 3: User Story 1 - 露出値を目盛りから選ぶ (P1 / MVP)

**Independent Test**: 通常/端/空/単一候補で中央保持と範囲外要求0件、即時反映と標準表記を確認。

- [x] T003 [US1] モデル境界テストを app/src/test/java/dev/cinematic/p0/ControlScaleTest.kt に追加する。(FR-001/003)
- [x] T004 [US1] 48dp矢印・56dp高・5候補・補助目盛りとドラッグを app/src/main/java/dev/cinematic/p0/ExposureControls.kt に実装する。(FR-001/002)
- [x] T005 [US1] core担当引継ぎ後、app/src/main/java/dev/cinematic/p0/CaptureScreen.kt のISO/SSを既存要求経路へ接続し反映ボタンを設けない。(FR-002/003/005)

## Phase 4: User Story 2 - 操作状態を読む (P2)

**Independent Test**: ISO/SSの2キーと選択・無効状態、縦/左右横持ち、撮影中/録画中/エラーとBack/AF遮断を確認。他キーは既存表示/挙動を維持。

- [x] T006 [US2] ISO/SSの72dpキー用ラベル/値/単位/タグ・選択背景/3dp線を app/src/main/java/dev/cinematic/p0/ExposureControls.kt に実装する。(FR-004/005)
- [x] T007 [US2] app/src/main/java/dev/cinematic/p0/CaptureScreen.kt のISO/SSへ接続し他キーの現行動作、状態区別、回転、既存tap遮断を維持する。(FR-004/005/006)

## Phase 5: Polish

- [x] T008 app/src/test/java/dev/cinematic/p0/ の関連unit/layout testとlintDebug/assembleDebug、git diff --checkを実行する。
- [x] T009 specs/008-control-scale-ui/tasks.md で実装済みと実機未検証を区別しconverge評価する。

## Dependencies & Execution Order

T001 → T002 → T003/T004 → T005。T006はT004後。T005/T007はcore通常mergeと編集権引継ぎ後。T008/T009は接続後。
並列可能: coreのCameraProbe制御とUI独立部品。CaptureScreen同時編集は禁止。
MVPはUS1、US2を追加し全体検証。公開/merge/配布はこのタスクに含めない。

## Phase 6: Convergence

- [ ] T010 Pixel実機でISO/SSの縦・左右横持ち×STILL/CINE、目盛り開閉時のプレビュー寸法、即時選択、撮影/録画/保存中の操作遮断とBack/AFを確認し、specs/008-control-scale-ui/tasks.md の実機残件を更新する。per SC-004 / US2/AC2 (partial)

## Validation

実装・機械的検証と実機受入を分ける。統合コードはcompileDebugKotlin、lintDebug、testDebugUnitTest（19 suites / 120 tests / failures 0 / errors 0）、assembleDebug、git diff --checkを通過した。
モデル境界4件、typed表示3件、既存layout/state・通常二段階stateを含む。UNKNOWNかつ撮影所有時のStopはbusy非依存で撮影画面・設定・診断から到達可能なコード経路を確認した。
実機の縦横レイアウト、連続操作、撮影・保存結果は未検証。採用HTMLの実像も標準browserのfile://拒否により未確認で、ソース構造/寸法との照合のみ。独立レビューと最終CIは別の受入条件。
ISO/SS以外のキー/ルーラーの装飾・操作変更は採用せず現行を維持。Issue #44の正常化に必要な状態表示と復旧導線だけを追加した。
