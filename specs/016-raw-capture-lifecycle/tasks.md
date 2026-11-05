# Tasks: RAW撮影後の保全と自動現像

**Input**: [spec.md](spec.md) / [plan.md](plan.md) / [contracts.md](contracts.md)。
状態は設計と実装を区別する。[保存専用Issue #89](https://github.com/reitojike/cinematic-camera/issues/89)で追跡する。
各項目の実装状態と実機確認を分ける。パスの`main`/`test`は`app/src/<種別>/java/dev/cinematic/p0/`を基準とする。

## Phase 1: Setup・設計PR

- [x] T001 `specs/016-raw-capture-lifecycle/spec.md`にUS/FR/SCと既存Issueとの境界を定義する。
- [x] T002 `specs/016-raw-capture-lifecycle/plan.md`/`contracts.md`に保全・再開・公開・#78契約を定義する。
- [x] T003 `.specify/memory/constitution.md`と`specs/002-live-capture/plan.md`へJPEG公開方針の変更を明記する。
- [x] T004 `specs/016-raw-capture-lifecycle/quickstart.md`に故障点と受入方法を対応付ける。
- [ ] T005 `specs/016-raw-capture-lifecycle/`を対象に#89の設計Draft PRを公開し、#78担当とファイル/契約境界を合意する。

## Phase 2: 共通基盤・独立policy PR

**独立検証**: 撮影/録画/未知状態ではstartしない。新予約とstartの競合を直列化する。

- [x] T006 `main/rawcapture/AutoDevelopPolicy.kt`に開始条件と未知値の抑止を実装する（FR-005）。
- [x] T007 `main/rawcapture/AutoDevelopCoordinator.kt`に撮影予約・単一attempt・協調停止/解放の区別を実装する（FR-005/006）。
- [x] T008 `test/rawcapture/AutoDevelopPolicyTest.kt`/`AutoDevelopCoordinatorTest.kt`で条件組合せ、旧完了、再開、予約raceを検証する（SC-003）。

この3項目は未接続のprocess-local制御だけ。T009以降の保存/引渡し/原本参照、実機SC-003の応答確認は未完了。

## Phase 3: US1・保全PRと撮影接続PR

**独立検証**: rendererなしでRAW/仮JPEGを保全でき、形式別部分失敗と再起動で成功分が残る。

- [x] T009 [US1] `main/rawcapture/CaptureJournal.kt`に版つき受領台帳と単一instance writer・root内の台帳path検証を実装する（FR-001/006/008）。
- [x] T010 [US1] `main/rawcapture/RawCaptureStore.kt`に形式別完了と中断時の保全を実装する（FR-001/002）。
- [ ] T011 [US1] `test/rawcapture/CaptureJournalTest.kt`/`RawCaptureStoreTest.kt`でclose/commit失敗・death・未知schema・不正pathを故障注入する（SC-001）。

T011の台帳側はfake backendのwrite/read失敗・commit応答喪失・破損・未知版・CAS競合・中断復旧と純pathを検証。
RawCaptureStoreは人工byte列の実一時ファイルでopen/部分write/flush/close、台帳commit前後の失敗、再生成、
既存directory/fileの非再利用、pathと所有handle、部分成功のバイト列保全を検証。
AndroidAtomicFile・実mediaの実端末障害/OS kill/電源断は未検証のためT011全体は未完了。
T009の受領metadataと版2の任意原本fingerprintは共有成功の証明ではない。DNG有効性とrecipeは別項目、共有引渡しはT012以降。
T010も撮影/起動走査へ未接続のstream保全部品。未知ファイルを削除/成功へ昇格せず、同サイズ改変の検出は別の内容照合処理の責務。
- [x] T012 [US1] `main/rawcapture/DngHandoffCoordinator.kt`と`MediaStoreDngPendingRows.kt`でDNGの1行公開、readback/hash一致、共有原本への引渡しを実装する（FR-003/007）。
- [x] T013 [US1] `test/rawcapture/DngInsertRecoveryTest.kt`/`DngHandoffTest.kt`でinsert応答前、URI記録、copy/close/pending解除/readback/解放台帳の故障を検証する（SC-001/002）。

T012/T013前段として`DngContentVerifier`と`RawCaptureStore.verifyOriginal`で全byteのsize/SHA-256、
任意fingerprintの版2台帳commit・版1読取り移行、内容readback照合と停止/IO/close/CAS/commit失敗を実装・人工データで検証。
初回hash以前の同サイズ改変やDNG decode有効性は証明しない。
insert部分は`DngPublicationJournal`/`DngInsertCoordinator`/`MediaStoreDngPendingRows`に分離し、公開intentのreadback後だけinsert、
pending行の所有/期待metadata照合、URI記録と不明時の非再insertを実装。`DngInsertRecoveryTest`で故障台帳/fake providerの中断・復旧を検証。
copy前のintent、入出力close後の読戻し、公開応答喪失の同URI採用、共有確認、解放intentと固定一時DNG削除を実装。
fault台帳/fake providerと人工byte列の実一時ファイルで検証し、API/実画像/OS kill/電源断と撮影接続の合格には数えない。
- [x] T014 [US1] `main/CameraProbe.kt`の新RAW保存を`RawCaptureSession`/`RawCaptureQueue`へ接続し、既存cleanup/timeout/late-callbackを形式別保全へ切替える（FR-001/002/003/005/008）。
- [x] T015 [US1] `test/rawcapture/RawCaptureSessionTest.kt`/`RawCaptureQueueTest.kt`/`RawCapturePresentationTest.kt`/`RawHandoffAdmissionTest.kt`で部分成功/重複/late/所有隔離、busy解除と共有成否の分離を検証する（SC-001/003）。

T014/T015はCameraX provided streamと形式別callbackへの接続、および人工byte列での保存/通知/予約・復旧検証。
Pixelの実撮影callback、実DNG decode、実OS kill/電源断、撮影応答/熱/容量の端末上の合格はT011/T021に残す。
自動現像rendererと現像JPEG公開はまだ接続しない。

## Phase 4: US2・公開拡張PRと#78統合PR

**独立検証**: fake rendererで成功JPEGだけを1件公開し、中断・再起動で原本/仮JPEGが残る。

- [ ] T016 [US2] `main/rawcapture/RenderRequest.kt`に合意した固定recipe/世代/参照を保存し、#78型とのadapter境界を定義する（FR-006/009）。
- [ ] T017 [US2] `main/rawcapture/AutoDevelopCoordinator.kt`に台帳からの列挙と1件ずつのrenderer実行を接続する（FR-004/005/006）。
- [ ] T018 [US2] `main/rawcapture/RecoverablePhotoPublisher.kt`へ検査済みJPEGの公開を追加する（FR-004/007）。
- [ ] T019 [US2] `test/rawcapture/AutoDevelopIntegrationTest.kt`で出力検査、旧generation、公開commit後の応答喪失、ユーザー削除後の非再作成を検証する（SC-001/002/003）。
- [ ] T020 [US2] `main/rawcapture/RendererAdapter.kt`で成立した#78のrendererを接続する。顔/露出不明は未対応として止め、仮の値で公開しない（FR-009）。

T018/T019の先行部分は`RenderedJpeg`成功receipt、`JpegPublicationJournal`、`RecoverablePhotoPublisher`と
Android検査/MediaStore adapterで実装する。固定recipe payload/source/世代/出力検査を公開台帳へ保持し、
fake providerによる各commit故障・応答喪失・古い世代・出力/原本改変・非再作成を検証する。
これは未接続の完成出力公開境界であり、T016のrender前の要求保存/列挙やT017/T020の自動現像成立を意味しない。
renderer契約とrecipe schemaの明示登録、既知の顔/露出成功が必要で、production契約はまだ登録しない。
T018/T019全体のcheckboxは統合とnative検証の状況に合わせて更新する。

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

- [x] T023 [US1] `main/rawcapture/RawCaptureStore.kt`に共有原本確認後だけの一時DNG解放、lease、削除失敗と再生成からの再開を実装する（FR-001/003）。
- [x] T024 [US1] `test/rawcapture/DngHandoffTest.kt`に同サイズ破損、解放台帳の前後/readback故障、正常時共有1/内部0、仮JPEG非削除を追加する（SC-001）。
- [ ] T025 [US2] `main/rawcapture/SharedOriginalAccess.kt`にread-only URI、hash照合、原本不在/変更/権限失効の区別と再関連付け契約を実装する（FR-010/011）。
- [ ] T026 [US2] `test/rawcapture/SharedOriginalAccessTest.kt`で開始/公開直前に検知できる外部移動/削除/同サイズ改変/失効と、選択取消/別内容再選択/台帳消失を検証する。最終照合後の非排他的な外部操作は別の限界として記録する（SC-005）。

T023〜024はT012〜013と同じ保全/公開PRで実施し、T014の撮影接続より前に完了する。
T025〜026はT016〜020より前の参照基盤。標準pickerのUI実装は#81等の後続だが、この参照/権限契約なしに
再インストール後の既存DNG編集を対応済みとは扱わない。

## Phase 7: 自動現像実行制御の先行統合

- [x] T027 [US2] `RenderRequest.kt`/`AutoDevelopJournal.kt`で固定request・世代・attempt・close後の検査結果を永続化し、未確認出力を昇格せず復旧する（T016先行部分、FR-006/009）。
- [x] T028 [US2] `AutoDevelopRunner.kt`/`AutoDevelopQueue.kt`で撮影優先・単一IO owner・非待機停止通知・実資源ack・明示retry・同じ成功artifactの公開再開を接続し、fault/fake renderer testsで確認する（T017/T019先行部分、FR-004/005/006/007）。
- [x] T029 [US2] `AndroidAutoDevelopBackend.kt`/`AndroidAutoDevelopQueue.kt`と共通`JpegOperationOwners`でread-only入力・attempt出力・#118 publisher・manual/auto二重writer拒否を実装し、隔離nativeの合成JPEGで確認する（FR-001/004/007/008/010）。

production renderer登録0・自動撮影接続なしを維持する。512pxの試験表示、顔NOT_RUN、診断露出、未知profileは自動公開しない。
T016/T017/T020、T025/026の残件と実機確認をこの先行統合のcheckboxで置き換えない。
T027〜029の検証はJVMのfault/fake rendererと、隔離API37のsynthetic adapterによるもの。実DNG decode/全解像度・色/顔の成立、実OS kill/電源断、Pixel性能の合格を含めない。

## Phase 8: 手動要求と共通ownerの統合

- [x] T030 [US2] `ManualDevelopRequests`/`ManualDevelopHandle`と既存queueに手動の固定source/recipe/縮小品質/永続ID・世代を接続し、commit不明/再配送/旧世代fenceを検証する。
- [x] T031 [US2] 既存runner/台帳に実出力観測、生成と明示publishの分離、同publisherのfresh確認を接続し、取消/後着retry/外部削除のfaultを検証する。
- [x] T032 [US2] `BoundedJpegRendererAdapter`/`AndroidOwnedJpegFrames`と同attempt readbackを実装し、共通admission・実FD close・原本不変を合成nativeで確認する。

production controls登録・photoroll接続・実観測・Pixel実機は上記の部品完了とは区別し、T020/021/025/026を完了にしない。

## Phase 9: Convergence

- [ ] T033 [US2] `photoroll`のpreview/手動UIを`DevelopExecution`と`ManualDevelopHandle`へ接続し、旧Semaphoreとの二重枠をなくす。選択/背景化の取消、latest条件、同owner status/publish/fresh確認、preview decoderの実close不明保持を検証する per FR-004/005/006/007/010・plan:共通の手動要求・実行・公開契約 (partial)。
- [ ] T034 [US2] T016/T017/T020/T025/T026の未接続部分を実source/recipe/providerと撮影lifecycleへ結び、起動時の完全台帳列挙、capture予約、foreground/camera/record/rebind/thermal/storage/memory観測、参照権再選択を検証する。顔/EV/profile未成立は登録せず、T021の実機測定とSC-004の許容差確定までカメラ使用中の現像を無効にする per FR-005/006/009/010/011・SC-003/004/005 (partial)。

## Phase 10: Private trial縦断接続

- [x] T035 [US2] 固定private-trial品質/recipeの専用handle・状態と同queue/実FD所有を接続し、復旧後もcompleted/publishへ進まないことを検証する（#129/#131、FR-005/006/008/009）。
- [x] T036 [US2] 同attemptのfresh private readbackと未claim/claim所有を接続し、取消直前/直後・世代変更・実close・同byte列decode・原本不変・共有公開0をtrial routeの単体/合成nativeで検証する（#129/#131、FR-001/004/005/006/008/010）。

## Phase 11: 起動時の現像要求復旧

- [x] T037 [US2] `AutoDevelopInventory`と既存Android queueの初期IOで全世代の台帳を検査・一括復旧する。未知/破損/redirect/不完全列挙はprocess owner全体を保留し、後着prepareより先に復旧する（T017/T034先行部分、FR-005/006/008）。
- [x] T038 [US2] 実一時directoryとfault台帳でsidecar、未知/破損新世代、ID/世代不一致、列挙失敗、初期化失敗後の投入拒否、manual/privateの非自動実行、中断retryと成功artifact保持を検証する（SC-001/002/003）。

完成renderer/固定recipe生成・撮影からの新規要求投入・Pixel性能/画質は今回の復旧部品に含めず、T017/020/021/034全体を完了にしない。

## Phase 12: 撮影時の自動現像意図

- [x] T039 [US2] 既存`CaptureReceipt`版3へ固定recipe/renderer契約/希望FINAL品質を任意保存し、旧版を意図なしとして読む。検証結果や成功出力を捏造せず4KiB/checksum/未知版保留を維持する（T016先行部分、FR-006/009）。
- [x] T040 [US2] 既存queue/session/storeのprepareで意図を受け渡し、台帳commit/readbackを出力openより先に完了する。受領・fingerprint・中断・復旧で不変保持し、既定入口は意図なしを維持する（FR-001/002/006/008）。
- [x] T041 [US2] 旧版opt-out、意図不変、完全/部分受領、中断/late/restart、create commit前後/readback喪失と出力open順序を人工ファイル・fault台帳で検証する（SC-001/002/003）。

撮影からの自動submit、実FINAL provider/全解像度メモリ・画質/Pixel性能はこの意図保存の完了に含めない。

## Phase 13: JPEG再読込のencoded snapshot

- [x] T042 [US2] 既知長を割当前に検証し、正確な1配列へchunk/EOF確認付きで読み込むprivate snapshotを実装する。未知長上限/取消/IO/close失敗とmutable非公開を維持する（FR-004/005/008/009）。
- [x] T043 [US2] private readbackのhash/検査/表示を同snapshotへ接続し、publisherは既知出力長を検査へ渡す。default inspectorも長さを検査し、原本照合/slot/世代/非公開条件を維持する（FR-001/004/005/007/010）。
- [x] T044 [US2] 既知short/long/零read/取消/read-close故障、未知長、source変更後のsnapshot不変、default長さ照合とnative JPEG/向き/破損/private回帰を確認する（SC-001/002/003/005）。

encoded配列の重複削減を全解像度renderer/codec内部コピー/Pixel性能の合格へ置換しない。
