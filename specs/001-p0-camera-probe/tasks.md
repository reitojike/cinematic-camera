# Tasks: P0 RAW・マニュアル露出の成立性検証

**Input**: [spec.md](spec.md)、[plan.md](plan.md)、既存実装とビルド/レビュー証拠。

`[x]`はその実装・文書・機械的検証が完了した印であり、実機AC合格の印ではない。
実機AC全体は未完了で、対応する作業を`[ ]`のまま残す。インストールとカメラ操作の成功は
ユーザー報告があるが、使用APK/hash、端末/Android build、camera IDは未提示。P1採否は未判定。

## Phase 1: Setup

- [x] T001 単一app、Kotlin/Compose、CameraX 1.6.2、固定ビルド版を `app/build.gradle.kts` / `build.gradle.kts` に設定する。
- [x] T002 CAMERA permissionと最小Activityを `app/src/main/AndroidManifest.xml` に定義する。

## Phase 2: Foundational

- [x] T003 privateセッションログとエラー表示の基盤を `app/src/main/java/dev/cinematic/p0/ProbeLog.kt` に用意する。
- [x] T004 ISO/時間範囲・ms→ns変換・能力判定を `app/src/main/java/dev/cinematic/p0/Exposure.kt` と `app/src/test/java/dev/cinematic/p0/ExposureTest.kt` で検証する。

## Phase 3: US1 — プレビューと能力表示 (Priority: P1)

**Goal**: 写真を撮らずに許可・lifecycleと各カメラの能力を確認できる。
**Independent Test**: 許可/拒否、開始/停止、背景復帰、各IDの能力と非対応表示。AC1/2を確認する。

- [x] T005 [US1] PreviewView、権限、lifecycle、Start/Stopを `app/src/main/java/dev/cinematic/p0/MainActivity.kt` に実装する。
- [x] T006 [US1] 各IDのCamera2能力とCameraX出力形式を `app/src/main/java/dev/cinematic/p0/CameraReport.kt` / `CameraProbe.kt` へ別々に記録する。
- [x] T007 [US1] ID変更時に形式集合を初期化し、CameraX非対応を `app/src/main/java/dev/cinematic/p0/CameraProbe.kt` のUI/logへ明示する。
- [ ] T008 [US1] Pixel 9aでAC1/2の許可・開始停止復帰・実測能力を確認し `docs/P0_REPORT.md` に端末/Android build/IDと結果を記録する。進捗: インストールとカメラ操作はユーザー報告あり、残る範囲と端末/版の同定は未確認。

## Phase 4: US2 — RAW素材の保存と有効性 (Priority: P1)

**Goal**: 対応形式だけでRAW素材を保存し、その有効性をPCで確認できる。
**Independent Test**: AUTOで5回保存し、全DNGをPC viewerで開く。MANUAL検証と別に実施できる。

- [x] T009 [US2] 対応確認後にRAW ImageCaptureを構築し、DNGを保存する `app/src/main/java/dev/cinematic/p0/CameraProbe.kt` の導線を実装する。
- [x] T010 [US2] RAW+JPEGの2出力callback、部分保存、停止後のLATE結果、token別の完了を `app/src/main/java/dev/cinematic/p0/CameraProbe.kt` に記録する。
- [ ] T011 [US2] まずRAWを1枚保存できるかとエラー表示を確認し、その後対応IDでDNGを5回以上保存して、PC viewerで各ファイルを開いた結果/サイズ/ファイル名を `docs/P0_REPORT.md` に記録する（AC3）。
- [ ] T012 [US2] AUTO JPEGとpreview、対応時のRAW+JPEG両出力、連続保存後/背景復帰後の撮影を `docs/P0_REPORT.md` のマトリクスで確認する。

## Phase 5: US3 — 手動露出とAUTO復帰 (Priority: P1)

**Goal**: 指定と実測を比較し、AUTO復帰で上書きが残らないことを確認できる。
**Independent Test**: ISOを固定時間で2値、時間を固定ISOで2値試し、安定後のrequest/resultとAUTO復帰を比較する。

- [x] T013 [US3] AE_OFF / ISO / exposure timeと範囲拒否を `app/src/main/java/dev/cinematic/p0/CameraProbe.kt` / `Exposure.kt` に実装する。
- [x] T014 [US3] AUTOと再bind時の明示clear、受付待ち/失敗時の撮影抑止を `app/src/main/java/dev/cinematic/p0/CameraProbe.kt` に実装する。
- [x] T015 [US3] Preview/ImageCaptureのCaptureResult、request、desired、世代によるcallback抑止を `app/src/main/java/dev/cinematic/p0/CameraProbe.kt` に実装する。
- [ ] T016 [US3] ISO/時間をそれぞれ2値以上で測り、指定との差・frame duration・制約を `docs/P0_REPORT.md` に記録する（AC4）。進捗: 操作できていそうとのユーザー報告はあるが、CaptureResult一致の証拠は未提示。
- [ ] T017 [US3] AUTO→MANUAL→AUTO、MANUAL→出力変更、Stop/Start、背景復帰を試し、停止/クラッシュ/制御競合の有無を `docs/P0_REPORT.md` に記録する（AC5）。

## Final Phase: 検証証拠と採否

- [x] T018 最小build/unit/lint CIとAPK配布を `.github/workflows/android.yml` / `p0-release.yml` に用意し、自然main CIと配布runを確認する。
- [ ] T019 不正入力・非対応・取得/保存/制御エラーを実機で確認し、安全なUI/logと回復結果を `docs/P0_REPORT.md` に記録する（AC6）。
- [x] T020 `docs/P0_REPORT.md` の結果記入欄・手順・制約と `docs/BUILD_VALIDATION.md` のビルド/レビュー証拠を作成する（AC7の文書部分のみ）。
- [ ] T021 実機証拠を反映し、必要なら根拠付きBLOCKEDを記録して、P1のGO/GO_WITH_LIMITATIONS/NO_GOを `docs/P0_REPORT.md` で判断する（AC7完了）。

## Dependencies & Execution Order

Setup → Foundational → US1の基本確認 → US2/US3の実機検証 → T019/T021のレポート判定。
US2とUS3はUS1のカメラ選択/能力を前提とするが、RAWのPC読み込みと露出の一致は別々の合否にする。
未対応の場合は該当taskへ実測したBLOCKED根拠をリンクし、チェックだけを進めない。

## Parallel Opportunities

単一端末の撮影や露出操作は並列実行しない。保存済みDNGのPC読み込み（US2）と、既存能力ログの
読み取り（US1）は独立して確認できる。US3の結果解析も保存済みログの読み取りとしては独立する。
同じ `docs/P0_REPORT.md` への結果反映は直列に行う。実機操作の無断実行や自動委任はしない。

## Implementation Strategy

最初はUS1の最小プレビュー/能力確認を行い、次にRAWとMANUALを個別に測る。現在は実装・機械的
検証の14 tasksが完了し、実機を必要とする7 tasksが残る。P0全体のconvergenceは未達であり、
後続フェーズの実装へ自動で進まない。テスト先行で作り直したとの記録や、実機合格を捏造しない。
