# Tasks: RAW撮影後の保全と自動現像

**Input**: [spec.md](spec.md) / [plan.md](plan.md) / [contracts.md](contracts.md)。
状態は設計と実装を区別する。[保存専用Issue #89](https://github.com/reitojike/cinematic-camera/issues/89)で追跡する。
以下のアプリ実装はすべて後続PR。パスの`main`/`test`は`app/src/<種別>/java/dev/cinematic/p0/`を基準とする。

## Phase 1: Setup・設計PR

- [x] T001 `specs/016-raw-capture-lifecycle/spec.md`にUS/FR/SCと既存Issueとの境界を定義する。
- [x] T002 `specs/016-raw-capture-lifecycle/plan.md`/`contracts.md`に保全・再開・公開・#78契約を定義する。
- [x] T003 `.specify/memory/constitution.md`と`specs/002-live-capture/plan.md`へJPEG公開方針の変更を明記する。
- [x] T004 `specs/016-raw-capture-lifecycle/quickstart.md`に故障点と受入方法を対応付ける。
- [ ] T005 `specs/016-raw-capture-lifecycle/`を対象に#89の設計Draft PRを公開し、#78担当とファイル/契約境界を合意する。

## Phase 2: 共通基盤・独立policy PR

**独立検証**: 撮影/録画/未知状態ではstartしない。新予約とstartの競合を直列化する。

- [ ] T006 `main/rawcapture/AutoDevelopPolicy.kt`に開始条件と未知値の抑止を実装する（FR-005）。
- [ ] T007 `main/rawcapture/AutoDevelopCoordinator.kt`に撮影予約・単一attempt・協調停止/解放の区別を実装する（FR-005/006）。
- [ ] T008 `test/rawcapture/AutoDevelopPolicyTest.kt`/`AutoDevelopCoordinatorTest.kt`で条件組合せ、旧完了、再開、予約raceを検証する（SC-003）。

## Phase 3: US1・保全PRと撮影接続PR

**独立検証**: rendererなしでRAW/仮JPEGを保全でき、形式別部分失敗と再起動で成功分が残る。

- [ ] T009 [US1] `main/rawcapture/CaptureJournal.kt`に版つき台帳と単一writer・root内参照検証を実装する（FR-001/006/008）。
- [ ] T010 [US1] `main/rawcapture/RawCaptureStore.kt`に形式別完了と中断時の保全を実装する（FR-001/002）。
- [ ] T011 [US1] `test/rawcapture/CaptureJournalTest.kt`/`RawCaptureStoreTest.kt`でclose/commit失敗・death・未知schema・不正pathを故障注入する（SC-001）。
- [ ] T012 [US1] `main/rawcapture/RecoverablePhotoPublisher.kt`でDNGの1行公開、readback/hash一致、共有原本への引渡しを実装する（FR-003/007）。
- [ ] T013 [US1] `test/rawcapture/RecoverablePhotoPublisherTest.kt`でinsert応答前、URI記録、copy/close/pending解除/readback/解放台帳の故障を検証する（SC-001/002）。
- [ ] T014 [US1] 別の接続PRで`main/CameraProbe.kt`の新RAW保存だけを接続し、既存cleanup/timeout/late-callbackを形式別保全へ切替える（FR-001/002/003/005/008）。
- [ ] T015 [US1] `test/rawcapture/RawCaptureIntegrationTest.kt`で部分成功/重複/late/世代隔離、busy解除とDNG共有成否の分離を検証する（SC-001/003）。

## Phase 4: US2・公開拡張PRと#78統合PR

**独立検証**: fake rendererで成功JPEGだけを1件公開し、中断・再起動で原本/仮JPEGが残る。

- [ ] T016 [US2] `main/rawcapture/RenderRequest.kt`に合意した固定recipe/世代/参照を保存し、#78型とのadapter境界を定義する（FR-006/009）。
- [ ] T017 [US2] `main/rawcapture/AutoDevelopCoordinator.kt`に台帳からの列挙と1件ずつのrenderer実行を接続する（FR-004/005/006）。
- [ ] T018 [US2] `main/rawcapture/RecoverablePhotoPublisher.kt`へ検査済みJPEGの公開を追加する（FR-004/007）。
- [ ] T019 [US2] `test/rawcapture/AutoDevelopIntegrationTest.kt`で出力検査、旧generation、公開commit後の応答喪失、ユーザー削除後の非再作成を検証する（SC-001/002/003）。
- [ ] T020 [US2] `main/rawcapture/RendererAdapter.kt`で成立した#78のrendererを接続する。顔/露出不明は未対応として止め、仮の値で公開しない（FR-009）。

## Phase 5: 検証・収束

- [ ] T021 [US1] [US2] `specs/016-raw-capture-lifecycle/quickstart.md`の実機手順を本人操作で確認し、#78/保存専用Issueへ非公開素材を含まない検証要約を記録する（SC-001〜005、FR-012）。
- [ ] T022 `specs/016-raw-capture-lifecycle/tasks.md`の実装/実機状態を更新し、各PRの最終headでlint/unit/build、canonical、独立明示OK、thread0を確認する。mergeは別承認。

## Dependencies & Implementation Strategy

設計→policy→保全/公開→撮影接続→#78統合。US1のMVPでは自動現像を有効化せず、原本保全を先に成立させる。
T012〜013は保全契約確定後にfake sourceで独立検証できるが、T014はT009〜013に依存する。
T016〜019は#78本体なしでもfake rendererで検証でき、T020は#78の画素比較/出力契約成立が前提。
US1の故障テストと#78の合成golden比較は別ファイルで並行可能。CameraProbe接続と共有型の同時編集は避ける。
生成・照合・cleanupの各テストは意図した故障で失敗を確認してから実装する。新依存や重い現像本体は追加しない。

## Phase 6: 共有原本1つへの引渡し・参照（接続前の必須作業）

- [ ] T023 [US1] `main/rawcapture/RawCaptureStore.kt`に共有原本確認後だけの一時DNG解放、lease、削除失敗とdeathからの再開を実装する（FR-001/003）。
- [ ] T024 [US1] `test/rawcapture/RawCaptureStoreTest.kt`に同サイズ破損、解放の全故障点、正常時共有1/内部0、仮JPEG非削除を追加する（SC-001）。
- [ ] T025 [US2] `main/rawcapture/SharedOriginalAccess.kt`にread-only URI、hash照合、原本不在/変更/権限失効の区別と再関連付け契約を実装する（FR-010/011）。
- [ ] T026 [US2] `test/rawcapture/SharedOriginalAccessTest.kt`で外部移動/削除/同サイズ改変/失効/選択取消/別内容再選択/台帳消失を検証する（SC-005）。

T023〜024はT012〜013と同じ保全/公開PRで実施し、T014の撮影接続より前に完了する。
T025〜026はT016〜020より前の参照基盤。標準pickerのUI実装は#81等の後続だが、この参照/権限契約なしに
再インストール後の既存DNG編集を対応済みとは扱わない。
