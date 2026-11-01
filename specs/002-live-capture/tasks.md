# Tasks: P0.5 撮影技術probe

**Issue**: [#8](https://github.com/reitojike/cinematic-camera/issues/8)

**Input**: [spec.md](spec.md) / [plan.md](plan.md)

対象はUS1とFR-002/003/004/010/011/013/014の限定実装。ルック・crop・製品UIは実装しない。
実装の完了と、V01〜V08の実機合格を区別する。進捗・レビューはIssue/PRコメントで管理する。

## Setup / 基盤

- [x] T001 CameraX1.6.2のcamera-video直接依存と音声permissionを `app/build.gradle.kts` / `app/src/main/AndroidManifest.xml` に追加する。
- [x] T002 能力候補の優先順位・ユーザー希望・fallbackと録画状態遷移を `app/src/main/java/dev/cinematic/p0/VideoPolicy.kt` に定義する。
- [x] T003 出力close/検証/pending公開/失敗cleanupを `app/src/main/java/dev/cinematic/p0/VideoPublication.kt` と `PendingVideoOutput.kt` に実装する。

## US1: 撮影と切替

- [x] T004 quality/dynamic range/組合せ別fpsとsession対応を `app/src/main/java/dev/cinematic/p0/CineSession.kt` で照会し、対応候補だけを構築する。
- [x] T005 Record/Stop/Finalize・音声状態・背景中断・遅延callbackを `app/src/main/java/dev/cinematic/p0/CineRecorder.kt` へ実装する。
- [x] T006 mode別ユーザー設定・露出・動画選択を `app/src/main/java/dev/cinematic/p0/CapturePreferences.kt` で保持する。
- [x] T007 `app/src/main/java/dev/cinematic/p0/CameraProbe.kt` へSTILL/CINE bind、AUTO解除/復元、静止画高解像度と出力metadataを統合する。
- [x] T008 `app/src/main/java/dev/cinematic/p0/MainActivity.kt` へ最小mode/動画候補/音声/録画状態UIと操作時のマイクpermissionを追加する。
- [x] T009 選択/fallback・世代付き録画状態・出力失敗cleanupを `app/src/test/java/dev/cinematic/p0/VideoPolicyTest.kt` / `VideoPublicationTest.kt` で検証する。

## 収束と実機境界

- [x] T010 build/unit/lintと独立レビューを実施し、検証結果と限界を `docs/P05_REPORT.md` へ記録する。最終headのcanonical review/必須CIは運用条件に従いPR/Issueで証跡を管理する。
- [ ] T011 ユーザー操作でV01/V06/V08と高解像度/fps/HDRの実機確認を行い、`docs/P05_REPORT.md` へ最終結果を統合する。自動検証で代用しない。

T001〜T003 → T004/T005/T006 → T007/T008 → T009/T010。T011はユーザー実機確認。
独立したファイルの調査は並行できるが、カメラ/マイクの自動作動は行わない。
P0証拠補完はIssue #4、運用設定はIssue #6。次のAF/WB/LED検証導線は別の限定Issueへ切り出す。

## B1: AF/AE・照明テレメトリーと熱状態（Issue #13）

- [x] T012 AF/AWB/anti-bandingとAE補正能力を `app/src/main/java/dev/cinematic/p0/CameraReport.kt` へ追加する。
- [x] T013 center AF/AE、AUTO時EV、世代分離とthermal listenerを `app/src/main/java/dev/cinematic/p0/CameraProbe.kt` へ実装する。
- [x] T014 要求/実測・focus/EV/熱状態の最小表示を `app/src/main/java/dev/cinematic/p0/MainActivity.kt` へ追加する。
- [x] T015 EV範囲/モード制約とthermal方針を `app/src/main/java/dev/cinematic/p0/ProbeControls.kt` / `app/src/test/java/dev/cinematic/p0/ProbeControlsTest.kt` で検証する。
- [x] T016 build/unit/lintと独立レビューを行い、`docs/P05_REPORT.md` へ確認結果・限界を記録する。canonical/CIの証跡はIssue/PRで管理する。
- [ ] T017 ユーザーがAF追従・AE補正・LED/照明・熱の実機条件を確認し、`docs/P05_REPORT.md` へ最終結果を統合する。自動検証で代用しない。

## B2: WB preset・MF・AE anti-banding（Issue #15）

- [x] T018 対応mode/request key/校正とMF範囲を `app/src/main/java/dev/cinematic/p0/CameraReport.kt` / `SensorOverrides.kt` へ定義する。
- [x] T019 単一snapshot・focus解除・AUTO全解除とmode別復元を `app/src/main/java/dev/cinematic/p0/CameraProbe.kt` へ統合する。
- [x] T020 対応候補/不適用条件と要求/結果の最小UIを `app/src/main/java/dev/cinematic/p0/MainActivity.kt` へ追加する。
- [x] T021 非対応・非finite・範囲端・独立fallbackを `app/src/test/java/dev/cinematic/p0/SensorOverridesTest.kt` で検証する。
- [x] T022 build/unit/lint・公開前検査・独立レビューを完了し、`docs/P05_REPORT.md` へ最終自動検証を記録する。最終headのcanonical/CIはIssue/PRで管理する。
- [ ] T023 ユーザーがWB色光比較、MF/AF往復、mode復元、AUTO全解除、LED条件を実機確認し、最終結果を `docs/P05_REPORT.md` へ統合する。

## B3: 検証レポートのSAF保存（Issue #17）

- [x] T024 UTF-8 log tail上限とclose完了条件を `app/src/main/java/dev/cinematic/p0/VerificationReport.kt` / `ProbeLog.kt` へ実装する。
- [x] T025 mainの状態snapshot、IO保存と遅延完了処理を `app/src/main/java/dev/cinematic/p0/CameraProbe.kt` へ実装する。
- [x] T026 ユーザー操作のCreateDocumentと取消/重複抑止を `app/src/main/java/dev/cinematic/p0/MainActivity.kt` へ追加する。
- [x] T027 tail/UTF-8とopen/write/flush/close失敗を `app/src/test/java/dev/cinematic/p0/VerificationReportTest.kt` で検証する。
- [x] T028 build/unit/lint・公開前検査・独立レビューを完了し、`docs/P05_REPORT.md` へ自動検証を記録する。最終headのcanonical/CIはIssue/PRで管理する。
- [ ] T029 ユーザーがSAF保存/取消/読み戻し/録画中抑止と、媒体本体を含まないことを端末で確認し、最終結果を `docs/P05_REPORT.md` へ統合する。

## UI/アイコン比較候補（Issue #19）

- [x] T030 3案の自己完結simulationを `designs/camera-ui-comparison.html`、比較軸と限界を `designs/README.md` へ用意する。
- [x] T031 desktop/狭幅render・keyboard・mode保持・guide/仮録画・通信/公開前検査と独立レビューを完了する。最終headのcanonical/CIはIssue/PRで証跡を管理する。
- [ ] T032 本人が配置/配色/アイコンを比較して最終選択する。未決定のままAndroid製品UIへ置換しない。

## B4: preview tap測光（Issue #21）

- [x] T033 `app/src/main/java/dev/cinematic/p0/PreviewFocusGesture.kt` へ標準tap認識とaccessibility click通知を追加する。
- [x] T034 `app/src/main/java/dev/cinematic/p0/CameraProbe.kt` へ中央/tap共通処理、公開対応照会、要求点/実regionの分離を実装する。
- [x] T035 `app/src/main/java/dev/cinematic/p0/ProbeControls.kt` / `app/src/test/java/dev/cinematic/p0/ProbeControlsTest.kt` でview座標変換/範囲/非finite/未準備を検証する。
- [x] T036 build/unit/lint・公開前検査・独立レビューを完了し、`docs/P05_REPORT.md` へ自動検証を記録する。最終headのcanonical/CIはIssue/PRで証跡を管理する。
- [ ] T037 ユーザーが中央/四隅/黒帯/drag・MF/AE・lens/mode/回転のmappingと実regionを確認し、最終結果を `docs/P05_REPORT.md` へ統合する。

## B5: 一時AE/AWB lock（Issue #23）

- [x] T038 能力/keyとAUTO条件・不適用時解除を `app/src/main/java/dev/cinematic/p0/AutoLocks.kt` / `CameraReport.kt` へ定義する。
- [x] T039 `app/src/main/java/dev/cinematic/p0/CameraProbe.kt` / `MainActivity.kt` へ一時要求・単一snapshot・実lock観測と最小UIを追加する。
- [x] T040 `app/src/test/java/dev/cinematic/p0/AutoLocksTest.kt` のgate/移行unit、build/lint・公開前検査・独立レビューを完了し、`docs/P05_REPORT.md` へ自動検証を記録する。最終headのcanonical/CIはIssue/PRで証跡を管理する。
- [ ] T041 ユーザーが照明変化・EV/手動/preset・mode/rebind・背景・動画での実lock/stateを確認し、最終結果を `docs/P05_REPORT.md` へ統合する。

## B6: 応答段階・sensor周期（Issue #25）

- [x] T042 `app/src/main/java/dev/cinematic/p0/CameraProbe.kt` へ写真callback/公開/UI通知の段階別elapsed時間を追加する。
- [x] T043 `app/src/main/java/dev/cinematic/p0/CineRecorder.kt` へRecord/Stop/terminal/IO/UI時間、`Exposure.kt` へ周期換算を追加する。
- [x] T044 `app/src/test/java/dev/cinematic/p0/ExposureTest.kt` の値変換unit、build/lint・公開前検査・独立レビューを完了し、`docs/P05_REPORT.md` へ自動検証を記録する。最終headのcanonical/CIはIssue/PRで証跡を管理する。
- [ ] T045 ユーザーが条件別のapp応答・sensor周期・MP4/描画fps・熱の関係を実機確認し、最終結果を `docs/P05_REPORT.md` へ統合する。

## 統合品質（Issue #29）

- [x] T046 session/録画/保存/復元を横断し、`Exposure.kt` / `CapturePreferences.kt` / `CameraProbe.kt` で受付済み完全pairの一括保存・復元を実装する。不完全pairはdraftで補わず、希望を保持してブロックする。
- [x] T047 `ExposureTest.kt` に完全/欠落/不正pairの回帰unitを追加し、`docs/P05_REPORT.md` にunitの保証境界とlint分類・優先実機条件を記録する。
- [x] T048 build/unit/lintの自動検証と保証範囲の整理を完了する。最終headの独立/canonical/必須CIはIssue/PRの運用gateで管理し、実機成功に拡大しない。
