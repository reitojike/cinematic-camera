# CPU・診断試験の整理台帳（#290）

## 状態と数え方

2026-11-14、監査元 main `4ee8067e77f9109baa0b5600ad4d7360b17a09d6`。
これは削除前の分類・移行計画であり、移行完了/試験合格の報告ではない。
#403 は同期後head `08c5487ed58343ba3ad6c90ce53f213f06cf78ec` で必要検証とレビューを完了し、
merge `28333f5f206cf18405f975e6fbbe0881c46d1694` でmainへ取り込まれた。
その renderer/pipeline の test-only 化を本台帳の133メソッドの移行完了件数に算入しない。

対象は #290 の残る `gpuDiagnosticTest` 17 Kotlin ファイル61 `@Test` メソッドと、
通常 `androidTest` の診断参照候補22ファイル72メソッド、計39ファイル133メソッド。
fixture/helper は件数に含めない。パラメータ組合せ・repeat数・GPU90条件をメソッド数へ加算しない。
通常側の候補は以下の式でファイルを選び、そのファイルの全 `@Test fun` を対象とした。
これは全リポジトリの CPU 参照/JVM/native/Mesa 試験を網羅した件数ではない。
削除PRで残存参照・sourceSets・テスト依存を再検索し、対象外の安全assertが見つかれば先に台帳へ追加する。

```text
dev\.cinematic\.p0\.(gpudiagnostic|rawdiagnostic|original24probe|previewdiagnostic)
OR BuildConfig\.BUILD_TYPE\s*[!=]=\s*"(gpuDiagnostic|rawDiagnostic|original24Probe|previewDiagnostic)"
OR GpuPreviewDiagnosticActivity
OR RawDiagnosticActivity
```

| 対象 | ファイル | メソッド | 移す/通常側で保持 M | 消す D | 既存他試験へ集約 E |
|---|---:|---:|---:|---:|---:|
| gpuDiagnosticTest | 17 | 61 | 46 | 6 | 9 |
| androidTest の診断参照候補 | 22 | 72 | 49 | 8 | 15 |
| 合計 | 39 | 133 | 95 | 14 | 24 |

- **M: 移す（GPU安全性）**。通常debugで必要なassertを実行可能にする。既に通常側で動く取消4件は「保持・再実走」で、ファイル移動/新規実装数には含めない。M1は入力/previewの46元メソッド、M2は保存/所有権の49元メソッド（保持4件を含む）。混在メソッドのCPU数値比較・旧UI配線は移さない。
- **D: 消す（CPU比較・旧probeだけ）**。当該実装/入口を廃止するPRで試験を一緒に削除する。試験だけ先に削除して現存実装の保証を空白にしない。生成済み原本・保存物・計測資産の削除を意味しない。
- **E: 既存の他試験で保証済み**は、保持するassertが既存ソースに存在するという分類。最終headの実走合格を意味しない。表の対応先を同じ削除候補headで実走してから重複試験を除く。対応先自身がM2の場合は、その通常側移行も先に完了させる。未実走/skip/失敗は削除を止める。

95は移行元メソッド数で、新しいテストを95件追加する見積りではない。同じ故障/所有権保証はまとめるが、異なる境界・12MP/24MP・実DocumentsUI/実一覧操作は小fixtureや直接state設定で置き換えない。

## 残りのPR構成と停止条件

実装は **移行2PR＋削除2PRの計4PR** を計画単位とする。台帳だけの追加PRは作らず、移行①へ同梱する。
これは今回分類した133件についての見通しであり、削除段階で発見する対象外依存やCI不成立を含めた確約ではない。
旧「移行③」は作らず、CPU比較/旧probe/重複部分を削除①②に吸収する。

1. **移行① M1: 入力・preview・GPU基盤**。46元メソッドの安全assertを通常androidTestへまとめる。native preflight/crop/AS_SHOT/BOX、入力取消・close所有権・source fence、picker/surface/Activity・GPU失敗/再試行、実gestureを含む。通常の実GPU/context、合成DNG、test専用hostを使い、廃止予定Activityを通常製品へ昇格させない。CPU worker/cache/比較bitmapの固有機構は再実装しない。
2. **移行② M2: 保存・所有権・故障**。49元メソッド（通常側保持4件を含む）の安全assertをまとめる。observer/consumer/codec/close失敗、同世代明示retry、新世代保存、原本/intent/journal、A保存中のB/C入力、FGS/Home/screen off、実12MP×5と24MPの成功/取消を含む。共有rasterの安全保証も保持し、CPU worker専用パリティは移さない。
3. **削除①: 診断host/variant/probe**。M1/M2の必要保証とEの対応先が実走済みになってから診断Activity/service/sourceSets/manifest/build入口・旧probeと対象重複試験を整理する。#397導入済みの通常1024px性能試験と引数検証を保持。#379元source/branchと [メモリ再実行手順](MEMORY_MEASUREMENT_REPLAY.md) は保持する。
4. **削除②: CPU preview/save renderer と不要参照**。M/Eの実走証拠を照合後にCPU render分岐、試験専用の旧renderer、CPU比較fixtureを整理。旧CPU保存intent/codec識別・読取りと再照合、GPU共有型・V1正本/生成器・LibRaw/JNI/AS_SHOT/入力解析・OriginalRasterを保持。旧CPU保存のGPU再生成、HOLD解除、二重保存を導入しない。

各移行PRの新テストのmethod名・実行件数・引数は実装差分確定後に追記する。統合した場合は本表のどの元メソッドのassertをどのmethodが担当するかを逆引き可能にする。名称を変えただけ、compileだけ、実行0件、skipは移行完了ではない。

試験のみ/文書のみは本人の更新指示「CI＋1系統レビュー」を適用する予定。現行 [AGENTS](../AGENTS.md) / [OPERATIONS](OPERATIONS.md) はcanonical＋独立OKを記載しているため差異をPRに明記し、規則本文を無断で削除しない。製品の観測hook・sourceSets・共有コード変更を含めば製品変更とし、90条件GPU全工程回帰と従来の最終CI/canonical/独立OK/未解決0/最新main/通常保護を満たす。文書/試験だけでも必須CIを免除しない。

## 実走証拠の取得

本台帳作成ではAndroid試験を新たに実行していない。静的照合133/133件は実行件数ではない。
#395/#397の性能、#398の保存境界2件、#399の観測2件と各GPU回帰は既存PRの証拠であり、新しい移行/削除headの成功へ流用しない。

### 移行①の実装着手（実走前）

`OddCropGeometryProbeTest` 4件、`Original24PreflightInstrumentationTest` 5件、
`Original24GpuInputAndroidTest` 3件の計12件を先に通常debug用の共通gateへ変更する。
`isolated=true` と `gpuInputSafety=true`、通常debug package、エミュレーターを必須とし、
不足条件はskipではなく失敗にする。すべてprivate合成DNGを使い、MediaStore/実機へ広げない。
この12件も実走成功までは移行完了に数えない。

入力3件のうち `inputOnlyEntryMatchesCpuSessionAsShotWithoutPreparingR7` は
`inputOnlyEntryLoadsNativeAsShotWithoutPreparingR7` へ変更し、CPU preview/lookの呼出しを除く。
既存native RGB16 decode→BOXの独立入力oracle、R7未解析、寸法/bit一致、owner解放、原本hashは保持する。
他の11メソッド名は変更しない。以下のコマンドは実行計画であり、成功記録ではない。

```text
adb -s <予約済みemulator> shell am instrument -w -r -e isolated true -e gpuInputSafety true -e class dev.cinematic.p0.jpegrender.original.OddCropGeometryProbeTest,dev.cinematic.p0.jpegrender.original.Original24PreflightInstrumentationTest,dev.cinematic.p0.jpegrender.original.Original24GpuInputAndroidTest dev.cinematic.p0.test/androidx.test.runner.AndroidJUnitRunner
```

期待12件。native入力試験であり、実GPU描画や90条件回帰の代替ではない。

続けて通常側へ追加する保証（旧診断試験は実走確認まで残す）:

| 通常側の新method | 移行元 | 実装範囲 |
|---|---|---|
| `GpuInputOwnershipSafetyAndroidTest.oldSurfaceDetachCannotClearNewAttachmentNotification` | `GpuPreviewLifecycleTest` の同名method | 旧surface detach/resizeで新attachment通知を消さず、GL終了確認後にsurfaceを解放 |
| `GpuInputOwnershipSafetyAndroidTest.contextRejectsWrongThreadDrawAndCloseAndClosedUseWithoutLosingOwner` | `GpuDiagnosticProbeTest.sessionReusesProgramsAndRejectsWrongThreadAndClosedUse` | 実EglContexts/PassGraphの誤thread draw/close拒否、program再利用、closed-use拒否、次context |
| `GpuInputOwnershipSafetyAndroidTest.unboundContextIsRejectedThenClosedBeforeNextContext` | `GpuDiagnosticProbeTest.unboundContextIsRejectedAndCanBeReleased` | unbind後の拒否・暗黙再bindなし・close後の次context |
| `GpuInputAdmissionSafetyAndroidTest.rejectedAndCancelledInputsKeepPrimaryFailureAndReleaseAdmissionBeforeNextInput` | `GpuDiagnosticDngInputTest` の事前取消/非local拒否/foreground未確認/cleanup取消による再分類禁止/cleanup失敗によるprimary置換禁止の5件 | 製品GpuPhotoRollInputs・private providerで各拒否後のread不開始/slot解放/原本不変、release observer取消/失敗後のprimary保持、最後のnative入力成功 |

この先行段階ではM1の20元メソッドに対応、通常側は計16method（既存12＋追加4）だった。現在の追加実装と残件は次の46件対応表を正本とする。
admissionの1methodは複数ケースをまとめたもので、5件実行と数えない。queueのprivate領域を隔離するため同classは新しいinstrumentation processで単独実行する。
両新classも `isolated=true` / `gpuInputSafety=true` が必要。新classの実GPU/入力試験は未実走。

先頭12件のcommit `c709a2cdd2a67561c3c1de2bc8fbf0eedea29cc6` で
`:app:compileDebugAndroidTestKotlin :app:testDebugUnitTest --no-daemon --max-workers=2 --stacktrace` は1m18sで成功。
unit taskはFROM-CACHEのため、新たなhost実走とは数えない。追加4件を含む最終headでコンパイルと対象host試験を取り直す。

- M1/M2で通常debug app/test APKを作成し、移行methodを明示選択する。opt-in引数を渡し、計画件数とinstrumentation結果を照合する。失敗/skipは0、成功件数は必ず1以上。12MP/24MP等の専用caseは別コマンド・別結果として記録する。
- head/tree、コマンド、APK hash、AVD/driver、開始終了時刻・所要時間、期待/成功/失敗/skip件数、観測した境界とcleanup結果を各PRに残す。ソースにassertがあることと実走成功を別欄で扱う。
- 製品変更/削除headで `tools/gpu-golden/capture_android.ps1` と `tools/gpu-golden/gpu_golden.py compare` を実行する。基準 `docs/gpu-golden-baselines/golden-20261112-131916-51b691ad` の90条件＋28帯比較を変更しない。
- [PERF] はgoldenとは独立の保証。`FULL_RECOMPUTE`/`GpuStageTap`の全13工程、1024×768、既定測定3frameを実走。実機を許可する引数実装は残すが、この作業で実機を操作しない。
- 通常Gradleとgolden wrapper内Gradleは同じworktree/buildで絶対に重ねない。2レーンは別worktree/build。共有AVDは予約→使用→終了/返却確認。#403初回の同時Gradleによるnative出力競合は双方停止後の単独再実行で解消した履歴として残し、成功ログに置換しない。
- 原本/取消/retry/owner、#365/#377/#357/#383の契約を変更しない。合成MediaStore行はowner/ID/nameを検証し成功時のみ削除、失敗/不明なら保持。ユーザーの保存済みデータは対象外。
- `OriginalRasterFile` の変更が必要なら追加レーンと先に調整する。本台帳は同ファイルを変更しない。#290を優先し、PR-Fは競合しない待ち時間のみ。

### 移行①の46元メソッド対応（追加実装後・全件Android未実走）

通常側の選択予定は **27 method**。入力解析/入力7+crop4+preflight5=16、GPU所有権3、admission2、routing3、lifecycle1、surface1、Files1。
これに既存 `GpuProductInputAndroidTest.originalIsReadOnlyAtOpenAndAllPreviewOwnersReleaseAcrossPhotosAndResume`、
`GpuProductEditorAndroidTest.slidersComparisonSelectionBackgroundAndRetryKeepOriginalAndOwnerBoundaries`、
`DevelopEngineAndroidTest.releasePolicyHasNoSettingAndDoesNotProbeGpu` の3件を保持先として再実走する（計30 method予定）。
各classはfresh instrumentation processで実行し、専用queue/observerの混在を避ける。予定件数を実績へ読み替えない。

元46件の内訳は、実装/保持対応42件、部分実装2件、契約差の代替保証2件。代替方針Aは本人承認済みだが、追加assertの実走完了前に旧保証を削除しない。**46件すべての移行完了を意味しない**。
42件にも既存通常試験への集約と、CPU/診断専用部分を移さない混在試験が含まれ、新規42件を意味しない。
旧分類133件の M95/D14/E24 は維持し、下表はM1内の実装状況を細分化したもの。

| 元class.method | 状態 | 通常側の保証対応/移さない部分 |
|---|---|---|
| `GpuDiagnosticDngInputTest.cancelledSelectionDoesNotReadOrRetainAdmission` | 実装/保持・未実走 | GpuInputAdmissionSafetyAndroidTest.rejectedAndCancelledInputsKeepPrimaryFailureAndReleaseAdmissionBeforeNextInput（実native入力、取消close待ち、FULL_RECOMPUTE。CPU reference log/旧start UIは削除対象） |
| `GpuDiagnosticDngInputTest.wrappedCancellationIsNotAReadFailure` | 実装/保持・未実走 | GpuInputAdmissionSafetyAndroidTest.productWaitPreservesCancellationAndReadFailureIdentity（製品future待機の実契約。診断専用recursive分類/null helperは移さない） |
| `GpuDiagnosticDngInputTest.trueReadFailureIsNotCancellation` | 実装/保持・未実走 | GpuInputAdmissionSafetyAndroidTest.productWaitPreservesCancellationAndReadFailureIdentity（製品future待機の実契約。診断専用recursive分類/null helperは移さない） |
| `GpuDiagnosticDngInputTest.cleanupCancellationDoesNotReclassifyAReadFailure` | 実装/保持・未実走 | GpuInputAdmissionSafetyAndroidTest.rejectedAndCancelledInputsKeepPrimaryFailureAndReleaseAdmissionBeforeNextInput（実native入力、取消close待ち、FULL_RECOMPUTE。CPU reference log/旧start UIは削除対象） |
| `GpuDiagnosticDngInputTest.cleanupListenerFailureDoesNotReplaceTheOriginalReadFailure` | 実装/保持・未実走 | GpuInputAdmissionSafetyAndroidTest.rejectedAndCancelledInputsKeepPrimaryFailureAndReleaseAdmissionBeforeNextInput（実native入力、取消close待ち、FULL_RECOMPUTE。CPU reference log/旧start UIは削除対象） |
| `GpuDiagnosticDngInputTest.nonLocalSelectionIsRejectedAndReleasesAdmissionForNextSelection` | 実装/保持・未実走 | GpuInputAdmissionSafetyAndroidTest.rejectedAndCancelledInputsKeepPrimaryFailureAndReleaseAdmissionBeforeNextInput（実native入力、取消close待ち、FULL_RECOMPUTE。CPU reference log/旧start UIは削除対象） |
| `GpuDiagnosticDngInputTest.unconfirmedForegroundCannotAcquireInputAdmission` | 実装/保持・未実走 | GpuInputAdmissionSafetyAndroidTest.rejectedAndCancelledInputsKeepPrimaryFailureAndReleaseAdmissionBeforeNextInput（実native入力、取消close待ち、FULL_RECOMPUTE。CPU reference log/旧start UIは削除対象） |
| `GpuDiagnosticDngPipelineTest.syntheticDocumentLoadsNativeAsShotAndRendersWithoutChangingOriginal` | 実装/保持・未実走 | GpuInputAdmissionSafetyAndroidTest.rejectedAndCancelledInputsKeepPrimaryFailureAndReleaseAdmissionBeforeNextInput（実native入力、取消close待ち、FULL_RECOMPUTE。CPU reference log/旧start UIは削除対象） |
| `GpuDiagnosticFilesSelectionTest.filesSelectsSyntheticDngAndRendersWithoutChangingOriginal` | 実装/保持・未実走 | GpuInputFilesSafetyAndroidTest.filesSelectsOnlyOwnedSyntheticDngAndProductGpuRendersIt（実DocumentsUI、実native/GPU、所有行照合） |
| `GpuDiagnosticPickerLifecycleTest.repeatedSelectionsThenPauseRejectAllLateResultsAfterResume` | 実装/保持・未実走 | GpuInputLifecycleSafetyAndroidTest.visibleContextLossRetryAndActivityTrimKeepOriginalAndRetireEveryPreviewOwner（実observer、12MP、10回再作成、実gesture、provider barrier中の新選択/pause/resume。旧synthetic/CPU worker UIは移さない） |
| `GpuDiagnosticPickerLifecycleTest.syntheticChoiceWinsOverCancelledPickerWork` | 実装/保持・未実走 | GpuInputLifecycleSafetyAndroidTest.visibleContextLossRetryAndActivityTrimKeepOriginalAndRetireEveryPreviewOwner（実observer、12MP、10回再作成、実gesture、provider barrier中の新選択/pause/resume。旧synthetic/CPU worker UIは移さない） |
| `GpuDiagnosticPickerLifecycleTest.failedGpuKeepsRetryVisibleUntilSelectedInputActuallyRenders` | 契約差・判断待ち | 旧診断のgpuError保持と製品の新session/入力loading表示は同じstateではない。新lifecycle試験はfailure可視→明示retry→実再描画を保持するが、load中も旧retryを保持するassertは未移行 |
| `GpuDiagnosticSurfaceInputTest.surfaceOnlyStopStartRendersAgain` | 実装/保持・未実走 | GpuInputSurfaceSafetyAndroidTest.surfaceRecreationAndInputDeliveredWithoutSurfaceRenderOnResume（実Compose surface、native入力、surface不在で新入力を渡してresume後描画） |
| `GpuDiagnosticSurfaceInputTest.foregroundSyntheticDngAfterRealPublicationRenders` | 実装/保持・未実走 | GpuInputLifecycleSafetyAndroidTest.visibleContextLossRetryAndActivityTrimKeepOriginalAndRetireEveryPreviewOwner（実observer、12MP、10回再作成、実gesture、provider barrier中の新選択/pause/resume。旧synthetic/CPU worker UIは移さない） |
| `GpuDiagnosticSurfaceInputTest.syntheticDngDeliveredWhileSurfaceAbsentRendersOnResume` | 実装/保持・未実走 | GpuInputSurfaceSafetyAndroidTest.surfaceRecreationAndInputDeliveredWithoutSurfaceRenderOnResume（実Compose surface、native入力、surface不在で新入力を渡してresume後描画） |
| `GpuDiagnosticSurfaceInputTest.delayedUiHiddenDuringResumedDngLoadDoesNotCancelNewInput` | 契約差・判断待ち | 旧診断は再開後UI_HIDDENでload継続。製品PhotoRollActivity.onTrimMemory/GpuPhotoRollEditorはpreviewTrimEpochでpreviewを閉じ明示retryを要求。旧期待値を移すには製品契約変更となるため未移行/未削除 |
| `GpuPreviewLifecycleTest.oldSurfaceDetachCannotClearNewAttachmentNotification` | 実装/保持・未実走 | GpuInputOwnershipSafetyAndroidTest.oldSurfaceDetachCannotClearNewAttachmentNotification |
| `GpuPreviewLifecycleTest.readinessTimeoutReplacementPauseAndDestroyKeepOnlyCurrentInput` | 実装/保持・未実走 | 既存 GpuProductEditorAndroidTest.slidersComparisonSelectionBackgroundAndRetryKeepOriginalAndOwnerBoundaries のtimeout/read不開始＋新 GpuInputLifecycleSafetyAndroidTest の旧callback/破棄確認に集約。両方の再実走が必要 |
| `GpuPreviewLifecycleTest.composeStopStartTrimAndTenActivityRecreationsRenderAgain` | 実装/保持・未実走 | GpuInputLifecycleSafetyAndroidTest.visibleContextLossRetryAndActivityTrimKeepOriginalAndRetireEveryPreviewOwner（実observer、12MP、10回再作成、実gesture、provider barrier中の新選択/pause/resume。旧synthetic/CPU worker UIは移さない） |
| `EditorPreviewParallelTest.workerCancellationClearsBothCachesAndRequiresNewSession` | 実装/保持・未実走 | Original24GpuInputAndroidTest.cancelledInputEntryIsTerminalAndRetainsNoTransientFiles（新sessionのnative成功を追加）。CPU worker/cache固有比較は移さない |
| `GpuProductLifecycleAndroidTest.visibleContextLossRetryAndActivityTrimKeepOriginalAndRetireEveryPreviewOwner` | 実装/保持・未実走 | GpuInputLifecycleSafetyAndroidTest.visibleContextLossRetryAndActivityTrimKeepOriginalAndRetireEveryPreviewOwner（実observer、12MP、10回再作成、実gesture、provider barrier中の新選択/pause/resume。旧synthetic/CPU worker UIは移さない） |
| `GpuDiagnosticProbeTest.sessionReusesProgramsAndRejectsWrongThreadAndClosedUse` | 実装/保持・未実走 | GpuInputOwnershipSafetyAndroidTest.contextRejectsWrongThreadDrawAndCloseAndClosedUseWithoutLosingOwner |
| `GpuDiagnosticProbeTest.unboundContextIsRejectedAndCanBeReleased` | 実装/保持・未実走 | GpuInputOwnershipSafetyAndroidTest.unboundContextIsRejectedThenClosedBeforeNextContext |
| `PreviewParallelDiagnosticTest.explicitStartCanCancelAndReleaseGate` | 実装/保持・未実走 | GpuInputAdmissionSafetyAndroidTest.rejectedAndCancelledInputsKeepPrimaryFailureAndReleaseAdmissionBeforeNextInput（実native入力、取消close待ち、FULL_RECOMPUTE。CPU reference log/旧start UIは削除対象） |
| `DevelopEngineAndroidTest.ordinaryContextIgnoresAllDiagnosticPreferencesWithoutMigration` | 実装/保持・未実走 | GpuInputRoutingSafetyAndroidTest.ordinaryContextIgnoresAllDiagnosticPreferencesWithoutMigration |
| `DevelopEngineAndroidTest.releasePolicyHasNoSettingAndDoesNotProbeGpu` | 部分実装・削除時確認待ち | 新 GpuInputRoutingSafetyAndroidTest.ordinaryPolicyCannotReadOrWriteLegacySelectionOrAdmitAChoice はroutingを保持。旧 DevelopEngineAndroidTest.releasePolicyHasNoSettingAndDoesNotProbeGpu は通常側で実走可能なため選択実行を保持。診断View削除時のentry不在検証への置換は未完 |
| `DevelopEngineAndroidTest.recreatedSettingsViewUsesTheSharedCapabilityResult` | 実装/保持・未実走 | GpuInputRoutingSafetyAndroidTest.capabilityResultIsSharedAcrossConsumersUntilExplicitRefresh（旧Viewなし、共有cacheを直接検証） |
| `OddCropGeometryProbeTest.oddCropPreservesPixelsAndOrientation` | 実装/保持・未実走 | OddCropGeometryProbeTest.oddCropPreservesPixelsAndOrientation（通常gateへ変更、assert保持） |
| `OddCropGeometryProbeTest.evenCropAndActiveAreaBoundariesPreservePixels` | 実装/保持・未実走 | OddCropGeometryProbeTest.evenCropAndActiveAreaBoundariesPreservePixels（通常gateへ変更、assert保持） |
| `OddCropGeometryProbeTest.invalidGeometryRemainsRejected` | 実装/保持・未実走 | OddCropGeometryProbeTest.invalidGeometryRemainsRejected（通常gateへ変更、assert保持） |
| `OddCropGeometryProbeTest.cancellationKeepsOriginalAndAllowsNextDecode` | 実装/保持・未実走 | OddCropGeometryProbeTest.cancellationKeepsOriginalAndAllowsNextDecode（通常gateへ変更、assert保持） |
| `Original24GpuInputAndroidTest.inputOnlyEntryMatchesCpuSessionAsShotWithoutPreparingR7` | 実装/保持・未実走 | Original24GpuInputAndroidTest.inputOnlyEntryLoadsNativeAsShotWithoutPreparingR7（CPU look比較を除去、native独立oracle維持） |
| `Original24GpuInputAndroidTest.cancelledInputEntryIsTerminalAndRetainsNoTransientFiles` | 実装/保持・未実走 | Original24GpuInputAndroidTest.cancelledInputEntryIsTerminalAndRetainsNoTransientFiles（新sessionのnative成功を追加）。CPU worker/cache固有比較は移さない |
| `Original24GpuInputAndroidTest.gpuInputReducesOriginalOnceToPixelLimitedDimensions` | 実装/保持・未実走 | Original24GpuInputAndroidTest.gpuInputReducesOriginalOnceToPixelLimitedDimensions |
| `Original24PreflightInstrumentationTest.existingNativeParserReportsExpectedRotationWithoutMovingBorrowedDescriptor` | 実装/保持・未実走 | Original24PreflightInstrumentationTest.existingNativeParserReportsExpectedRotationWithoutMovingBorrowedDescriptor（通常gateへ変更、assert保持） |
| `Original24PreflightInstrumentationTest.existingNativeParserRejectsCyclesAmbiguousRawIfdsAndDuplicateCriticalTags` | 実装/保持・未実走 | Original24PreflightInstrumentationTest.existingNativeParserRejectsCyclesAmbiguousRawIfdsAndDuplicateCriticalTags（通常gateへ変更、assert保持） |
| `Original24PreflightInstrumentationTest.integralDefaultCropReportsAbsoluteOffsetsAlongWithOutputDimensions` | 実装/保持・未実走 | Original24PreflightInstrumentationTest.integralDefaultCropReportsAbsoluteOffsetsAlongWithOutputDimensions（通常gateへ変更、assert保持） |
| `Original24PreflightInstrumentationTest.existingNativeParserRejectsFractionalCropNonunitScaleAndUnknownUserCrop` | 実装/保持・未実走 | Original24PreflightInstrumentationTest.existingNativeParserRejectsFractionalCropNonunitScaleAndUnknownUserCrop（通常gateへ変更、assert保持） |
| `Original24PreflightInstrumentationTest.cancellationAndNonclassicTiffAreRejectedBeforeOwnerAdmission` | 実装/保持・未実走 | Original24PreflightInstrumentationTest.cancellationAndNonclassicTiffAreRejectedBeforeOwnerAdmission（通常gateへ変更、assert保持） |
| `Original24PreviewSessionInstrumentationTest.optionalComparisonOomDoesNotMaskSourceCancelCloseAdmissionOrIoFailure` | 実装/保持・未実走 | Original24GpuInputAndroidTest.inputFaultsCannotBecomeOptionalComparisonSuccessOrRestart＋同classのclose所有権試験。CPU comparison Bitmap/OOM fallbackは移さない |
| `Original24PreviewSessionInstrumentationTest.sourceChangeFailsBeforeReuseAndNeverRestartsFailedSession` | 実装/保持・未実走 | Original24GpuInputAndroidTest.changedOriginalIsRejectedAtColdInputAndFailedSessionCannotRestart＋既存 GpuProductInputAndroidTest.originalIsReadOnlyAtOpenAndAllPreviewOwnersReleaseAcrossPhotosAndResume。GPU一度限りの入力にCPU warm session再利用を導入しない |
| `Original24PreviewSessionInstrumentationTest.failedSourceCloseRemainsOwnedAndCloseRetriesExactStream` | 実装/保持・未実走 | Original24GpuInputAndroidTest.failedSourceCloseRetainsExactStreamUntilCloseIsAcknowledged |
| `Original24PreviewSessionInstrumentationTest.independentDecodeRejectsSameSizeCropAndOrientationMismatch` | 実装/保持・未実走 | Original24GpuInputAndroidTest.sameSizeCropAndOrientationMismatchCannotProduceGpuInput |
| `Original24PreviewSessionInstrumentationTest.draftDefersSourceFailureUntilExactWhileCancellationStillClearsAllDecisions` | 実装/保持・未実走 | Original24GpuInputAndroidTest のchangedOriginal/cancelledInput/failedSourceClose 3件へ集約。CPU draft遅延検証は移さない |
| `Original24PreviewSessionInstrumentationTest.metadataFencesAvoidWarmSourceReadsAndKeepPixelsAndStrongColdBoundary` | 部分実装・M2対応待ち | 新 changedOriginalIsRejectedAtColdInputAndFailedSessionCannotRestart＋既存 GpuProductInputAndroidTest のwarm frame/read不開始を使用。実cold保存境界はM2との対応確認が必要 |
| `PreviewRawGestureFixtureAndroidTest.dragAndReleaseReachRealSyntheticRawPreview` | 実装/保持・未実走 | GpuInputLifecycleSafetyAndroidTest.visibleContextLossRetryAndActivityTrimKeepOriginalAndRetireEveryPreviewOwner（実observer、12MP、10回再作成、実gesture、provider barrier中の新選択/pause/resume。旧synthetic/CPU worker UIは移さない） |

契約差の2件を「旧probe」という名前だけで削除しない。製品変更なしで維持できる安全境界を確認し、
診断固有の表示/trim継続仕様を廃止するかは記録して判断する。製品コードを旧診断の期待値へ合わせる変更は行っていない。
AVDは他レーン利用中。未予約のemulatorには接続・停止・導入せず、初回30件の実走は返却調整後に実施する。

## 既存の保持先（Eの参照）

以下は監査元に存在するmethod。Mへ集約する保証の補助にも使うが、表にないassertまで同等と推定しない。

- **[CACHE]**: [StageClearGpuPipelineAndroidTest.strengthReusesBothCachesAndMatchesFreshRgbaBitsAtBothSizes](../app/src/androidTest/java/dev/cinematic/p0/gpu/StageClearGpuPipelineAndroidTest.kt)。削除headで再実走。
- **[INPUT]**: [GpuProductInputAndroidTest.originalIsReadOnlyAtOpenAndAllPreviewOwnersReleaseAcrossPhotosAndResume](../app/src/androidTest/java/dev/cinematic/p0/photoroll/GpuProductInputAndroidTest.kt)。削除headで再実走。
- **[EDITOR]**: [GpuProductEditorAndroidTest.slidersComparisonSelectionBackgroundAndRetryKeepOriginalAndOwnerBoundaries](../app/src/androidTest/java/dev/cinematic/p0/photoroll/GpuProductEditorAndroidTest.kt)。削除headで再実走。
- **[ROTATE]**: [GpuPreviewAndroidTest.presentationPreservesTopLeftRowsAndAllClockwiseQuarterTurns](../app/src/androidTest/java/dev/cinematic/p0/gpu/GpuPreviewAndroidTest.kt)。削除headで再実走。
- **[CONTEXT]**: [GpuProductContextLossAndroidTest.submitQueuedBeforeContextLossCannotReopenBehindFailureAndExplicitRetryChecksOriginalAgain](../app/src/androidTest/java/dev/cinematic/p0/photoroll/GpuProductContextLossAndroidTest.kt)。削除headで再実走。
- **[BANDS]**: [GpuExportRendererAndroidTest.finalArgbAndGlobalScalarsDoNotDependOn64Or256RowBands](../app/src/androidTest/java/dev/cinematic/p0/gpu/GpuExportRendererAndroidTest.kt)。削除headで再実走。
- **[CANCEL]**: [GpuExportRendererAndroidTest.cancellationAtEveryBoundaryReleasesTexturesAndPermitsFinalRetry](../app/src/androidTest/java/dev/cinematic/p0/gpu/GpuExportRendererAndroidTest.kt)。削除headで再実走。
- **[EXPORT-FAIL]**: [GpuExportRendererAndroidTest.consumerFailureAndCancellationReleaseAllTexturesAndPermitSameRendererRetry](../app/src/androidTest/java/dev/cinematic/p0/gpu/GpuExportRendererAndroidTest.kt)。削除headで再実走。
- **[FOUNDATION]**: [GpuFoundationAndroidTest.rgba32fRoundTripIsBitExactAndIncludeProgramIsCached](../app/src/androidTest/java/dev/cinematic/p0/gpu/GpuFoundationAndroidTest.kt)。削除headで再実走。
- **[FOUNDATION-CLOSE]**: [GpuFoundationAndroidTest.contextResourcesCloseTogetherAndFailedTextureCloseKeepsAccounting](../app/src/androidTest/java/dev/cinematic/p0/gpu/GpuFoundationAndroidTest.kt)。削除headで再実走。
- **[PERF]**: [GpuFullPipelineTimingAndroidTest.measure1024FullRecompute](../app/src/androidTest/java/dev/cinematic/p0/gpu/GpuFullPipelineTimingAndroidTest.kt)。削除headで再実走。
- **[OBSERVE]**: [GpuPreviewObserverSafetyAndroidTest.timingPreservesOutputBitsAndSource](../app/src/androidTest/java/dev/cinematic/p0/gpu/GpuPreviewObserverSafetyAndroidTest.kt) / [GpuPreviewObserverSafetyAndroidTest.enabledGatePreservesOutputBitsAndSource](../app/src/androidTest/java/dev/cinematic/p0/gpu/GpuPreviewObserverSafetyAndroidTest.kt)。削除headで再実走。
- **[BOUNDARY]**: [Original24GpuBoundaryCancellationAndroidTest.cancelInsideSecondFinalBandRetriesSameGenerationAndReleasesTextures](../app/src/androidTest/java/dev/cinematic/p0/jpegrender/original/Original24GpuBoundaryCancellationAndroidTest.kt) / [Original24GpuBoundaryCancellationAndroidTest.cancelAfterSecondB1BandRetriesSameGenerationAndReleasesTextures](../app/src/androidTest/java/dev/cinematic/p0/jpegrender/original/Original24GpuBoundaryCancellationAndroidTest.kt)。削除headで再実走。
- **[GPU-SAVE]**: [Original24GpuSaveInstrumentationTest.gpuSaveKeepsStartSelectionAndReconcilesOneVerifiedPublicJpeg](../app/src/androidTest/java/dev/cinematic/p0/jpegrender/original/Original24GpuSaveInstrumentationTest.kt)。対応先もM2の通常側移行待ち。

## 診断側 17ファイル61件

### [GpuDiagnosticDngInputTest](../app/src/gpuDiagnosticTest/java/dev/cinematic/p0/gpu/GpuDiagnosticDngInputTest.kt) — 7件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `cancelledSelectionDoesNotReadOrRetainAdmission` | M1 | 取消済み入力の read 不開始・admission 解放を製品 GpuPhotoRollInputs の試験へ。 |
| `wrappedCancellationIsNotAReadFailure` | M1 | 多重 wrapper 内の取消を read failure と区別する保証を製品入力失敗分類へ。診断 helper 自体は残さない。 |
| `trueReadFailureIsNotCancellation` | M1 | 本当の IOException/null/壊れた cause accessor を取消扱いしない製品入力分類へ。 |
| `cleanupCancellationDoesNotReclassifyAReadFailure` | M1 | cleanup 用 cancel が元の read failure を再分類しない保証を製品入力へ。 |
| `cleanupListenerFailureDoesNotReplaceTheOriginalReadFailure` | M1 | cancel listener の例外で元の read failure を置換しない保証を製品入力へ。 |
| `nonLocalSelectionIsRejectedAndReleasesAdmissionForNextSelection` | M1 | 非ローカル選択拒否後、次の入力が admission を得る保証を製品入力へ。 |
| `unconfirmedForegroundCannotAcquireInputAdmission` | M1 | foreground 未確認では入力 admission を取得しない保証を製品入力へ。 |

### [GpuDiagnosticDngPipelineTest](../app/src/gpuDiagnosticTest/java/dev/cinematic/p0/gpu/GpuDiagnosticDngPipelineTest.kt) — 1件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `syntheticDocumentLoadsNativeAsShotAndRendersWithoutChangingOriginal` | M1 | native AS_SHOT→実 FULL_RECOMPUTE、寸法、readonly FD/原本 hash、ledger 解放を製品入力試験へ。CPU reference の差分ログは削除。 |

### [GpuDiagnosticFilesSelectionTest](../app/src/gpuDiagnosticTest/java/dev/cinematic/p0/gpu/GpuDiagnosticFilesSelectionTest.kt) — 1件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `filesSelectsSyntheticDngAndRendersWithoutChangingOriginal` | M1 | 実 DocumentsUI から合成 DNG を選択し GPU 描画・原本不変を確認する通常 test host へ。provider 直渡しだけでは代替しない。 |

### [GpuDiagnosticPickerLifecycleTest](../app/src/gpuDiagnosticTest/java/dev/cinematic/p0/gpu/GpuDiagnosticPickerLifecycleTest.kt) — 3件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `repeatedSelectionsThenPauseRejectAllLateResultsAfterResume` | M1 | 連続選択→pause/resume 後に古い callback が入力/失敗表示を上書きしない保証を製品選択へ。 |
| `syntheticChoiceWinsOverCancelledPickerWork` | M1 | 新しい写真選択が取消済み picker work に勝つ保証を製品選択へ。旧 synthetic 選択 UI は削除。 |
| `failedGpuKeepsRetryVisibleUntilSelectedInputActuallyRenders` | M1 | GPU failure の表示/再試行を次入力の実描画完了まで消さない保証を製品 UI へ。 |

### [GpuDiagnosticSurfaceInputTest](../app/src/gpuDiagnosticTest/java/dev/cinematic/p0/gpu/GpuDiagnosticSurfaceInputTest.kt) — 4件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `surfaceOnlyStopStartRendersAgain` | M1 | Compose surface 消失/再作成と実再描画を通常 test host へ。 |
| `foregroundSyntheticDngAfterRealPublicationRenders` | M1 | 実 foreground publication 後の入力 admission と描画を通常 test host へ。foreground override だけに縮小しない。 |
| `syntheticDngDeliveredWhileSurfaceAbsentRendersOnResume` | M1 | surface 不在で届いた入力を resume まで保留し正しい epoch で描画する保証を通常 host へ。 |
| `delayedUiHiddenDuringResumedDngLoadDoesNotCancelNewInput` | M1 | 遅延 UI_HIDDEN が再開後の新しい DNG load を取消さない保証を通常 host へ。 |

### [GpuPreviewLifecycleTest](../app/src/gpuDiagnosticTest/java/dev/cinematic/p0/gpu/GpuPreviewLifecycleTest.kt) — 4件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `oldSurfaceDetachCannotClearNewAttachmentNotification` | M1 | 古い surface の detach/resize が新しい attached 通知を消さない実 renderer 試験をそのまま通常側へ。 |
| `readinessTimeoutReplacementPauseAndDestroyKeepOnlyCurrentInput` | M1 | readiness timeout/入力置換/pause/destroy で現在入力のみ保持する保証を製品 host へ。 |
| `composeStopStartTrimAndTenActivityRecreationsRenderAgain` | M1 | Compose stop/start・trim と Activity 再作成10回の window/ledger/renderer 所有権を通常 host へ。 |
| `diagnosticSlidersBeforeAndRotationReachRendererThroughAccessibilityActions` | E | [EDITOR] の3調整/比較操作と [ROTATE] の全 quarter turn が保持先。診断専用 slider/rotate ボタンの UI 配線は廃止対象。 |

### [EditorPreviewParallelTest](../app/src/gpuDiagnosticTest/java/dev/cinematic/p0/jpegrender/original/EditorPreviewParallelTest.kt) — 2件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `exactAndDraftMatchAcrossStrengthWarmthAndEvEdits` | E | [CACHE] の GPU cached/fresh bits・調整無効化と [INPUT] の原本/FD 解放で保持。CPU exact/draft の worker 数比較・CPU fields は削除。 |
| `workerCancellationClearsBothCachesAndRequiresNewSession` | M1 | 取消後の owner 解放・閉じた session の再利用拒否・新 session の再試行を GPU preview で明示確認。CPU 並列 worker/cache の固有比較は削除。 |

### [GpuConcurrentMemoryProbeTest](../app/src/gpuDiagnosticTest/java/dev/cinematic/p0/jpegrender/original/GpuConcurrentMemoryProbeTest.kt) — 2件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `measureSaveBandAndNextPhoto` | M2 | 保存 A の実 band 保持中に B の入力/preview が独立し、A が同じ request で公開する保証を通常側へ。メモリ手順も保持。 |
| `cancelNextPhotoWhileSaveBandIsHeld` | M2 | B 取消が保持中 A の export/原本/owner を解放しない保証を通常側へ。小さい fixture の結果を大サイズ計測結果にしない。 |

### [Original24GpuObserverFailureAndroidTest](../app/src/gpuDiagnosticTest/java/dev/cinematic/p0/jpegrender/original/Original24GpuObserverFailureAndroidTest.kt) — 5件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `renderFailureRemainsPrimaryWhenReleaseObserverAlsoFails` | M2 | render failure を primary として release observer の失敗を suppressed に残す試験を通常側へ。 |
| `suppressedCleanupFailureCannotBecomeConfirmedWhenReleaseObserverFails` | M2 | cleanup failure が observer failure で cleanupConfirmed=true へ化けない試験を通常側へ。 |
| `cancelledRenderKeepsUnknownCleanupWhenReleaseObserverFails` | M2 | 取消でも unknown cleanup を HOLD として保持する試験を通常側へ。 |
| `releaseObserverRethrowingSameFailureKeepsCleanupEvidence` | M2 | 同一 Throwable の再 throw で self-suppression/cleanup 証拠消失を起こさない試験を通常側へ。 |
| `releaseObserverFailureAfterSuccessfulRenderStillCompletesAndCloses` | M2 | 正常描画後 observer failure でも rows 完了・ledger0・thread終了を確認する試験を通常側へ。 |

### [CpuExportPhotoSwitchAndroidTest](../app/src/gpuDiagnosticTest/java/dev/cinematic/p0/photoroll/CpuExportPhotoSwitchAndroidTest.kt) — 1件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `acceptedCpuSaveAllowsRealListSwitchAndBothPhotosPublish` | M2 | 実ロール一覧 A→B 操作、A の FGS 継続、B recipe 保持、暗黙保存なし、各写真の明示保存と別 URI を GPU に移す。CPU witness/GPU0 は削除。 |

### [GpuExportActiveOwnershipAndroidTest](../app/src/gpuDiagnosticTest/java/dev/cinematic/p0/photoroll/GpuExportActiveOwnershipAndroidTest.kt) — 10件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `activeGpuExportSurvivesPreviewTrimCallbacksWithoutReopeningPreview` | M2 | preview trim が A の export ticket/intent/raster を壊さず暗黙 preview 再開しない保証を通常側へ。 |
| `oldGpuExportCompletionCannotPublishIntoNewKeyedPhotoEditor` | M2 | A の遅延公開が B editor に混入せず、B は明示保存で別世代/URI になる保証を通常側へ。 |
| `reselectingActiveAClosesNewPreviewAndRequiresExplicitResumeAfterPublication` | M2 | 保存中 A の再選択で新 preview を閉じ、公開後も明示 resume まで再読込しない保証を通常側へ。 |
| `switchingDisplayedBToCKeepsAExportAndClosesB` | M2 | B→C 切替で B のみ閉じ A export を維持する保証を通常側へ。 |
| `backgroundAfterBPresentationClosesPreviewWithoutCancellingA` | M2 | B 描画後の背景化で B preview のみ閉じ A export を維持する保証を通常側へ。 |
| `contextLossOnDisplayedBDoesNotCancelOrReopenA` | M2 | B 描画後の context loss が A を取消/再開しない保証を通常側へ。 |
| `contextLossOnBFirstDrawDoesNotCancelOrReopenA` | M2 | B 初回描画の context loss が A を取消/再開しない保証を通常側へ。 |
| `switchingBWhilePreflightOwnsSnapshotDrainsOnlyBAndKeepsA` | M2 | B preflight が snapshot を保持中の切替で B のみ drain し A を保持する保証を通常側へ。 |
| `backgroundWhileBPreflightOwnsSnapshotDrainsOnlyBAndKeepsA` | M2 | B preflight が snapshot を保持中の背景化で B のみ drain し A を保持する保証を通常側へ。 |
| `exportContextLossWhileBPreflightIsHeldDoesNotReleaseBInput` | M2 | B preflight 保持中の A export context loss が B の input lease を解放しない保証を通常側へ。 |

### [GpuExportSafetyLifecycleAndroidTest](../app/src/gpuDiagnosticTest/java/dev/cinematic/p0/photoroll/GpuExportSafetyLifecycleAndroidTest.kt) — 2件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `real12MpSavePublishesWhileScreenOffWithoutImplicitPreviewRestart` | M2 | 実12MP・screen off中公開・FGS前preview終了・復帰後明示resumeを通常側へ。小サイズの成功で置換しない。 |
| `recoveredPseudoContextLossUsesRealUiRetryAndSame12MpGeneration` | M2 | 実12MP context loss 後に UI 明示retryで同世代/intent/recipeを保ち公開、失敗数1回・自動retryなしを通常側へ。 |

### [GpuProductLifecycleAndroidTest](../app/src/gpuDiagnosticTest/java/dev/cinematic/p0/photoroll/GpuProductLifecycleAndroidTest.kt) — 1件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `visibleContextLossRetryAndActivityTrimKeepOriginalAndRetireEveryPreviewOwner` | M1 | 可視 failure→明示retry、trim/背景/再開/別写真、原本不変と全preview owner解放を通常側へ。[CONTEXT] だけでは Activity trim 一式を代替しない。 |

### [GpuSaveLifecycleAndroidTest](../app/src/gpuDiagnosticTest/java/dev/cinematic/p0/photoroll/GpuSaveLifecycleAndroidTest.kt) — 1件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `gpuEditorSaveSurvivesHomeAndReturnWithoutImplicitPreviewRestart` | M2 | Home→復帰→背景の間の実GPU保存、公開照合の冪等性/二重公開なし、明示preview再開を通常側へ。 |

### [GpuDiagnosticLoadTest](../app/src/gpuDiagnosticTest/java/dev/cinematic/p0/raw/GpuDiagnosticLoadTest.kt) — 1件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `syntheticEntrypointsComplete` | D | 旧 diagnostic service の preview/blur12/orton12 load entry の完了確認。廃止する入口専用。通常全工程性能試験 [PERF] とメモリ再実行手順は別に保持。 |

### [GpuDiagnosticProbeTest](../app/src/gpuDiagnosticTest/java/dev/cinematic/p0/raw/GpuDiagnosticProbeTest.kt) — 11件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `integratedEntrypointsDoNotStartGpu` | D | 旧診断 Activity 群の起動で旧 probe service が始まらない保証。削除する入口専用。 |
| `repeatedStartsCancelAndStopService` | M2 | 旧 service 固有の反復起動は削除するが、取消後の worker終了・FGS停止を通常保存の反復取消試験に残す。 |
| `blurMatchesBandBoundariesAndReportsCpuReference` | E | [BANDS] が GPU band 分割非依存を保持。旧 CPU blur/reference 比較とログは削除。90条件＋28帯比較も削除PRで実走。 |
| `preparedCpuMatchesOriginalOrton` | D | prepared CPU と OriginalOrton の数値一致だけを比較する旧 CPU 試験。 |
| `cancellationAndNextContext` | E | [CANCEL] の各実GPU境界での取消・解放・最終retryが保持先。旧 GpuBlurProbe の起動形態は削除。 |
| `combinedLookReportsCpuReferenceAndPreservesGpuReuse` | E | [CACHE] の強度/温度/EV変更と fresh/reused bits が保持先。旧 CPU reference ログは削除。 |
| `sessionReusesProgramsAndRejectsWrongThreadAndClosedUse` | M1 | program再利用は [FOUNDATION] にあるが、wrong-thread close/draw と closed-use の全assertは完全一致と断定しない。実EglContexts/GlThread側で補う。 |
| `consumerFailureAndCancellationReleaseReusableSession` | E | [EXPORT-FAIL] のconsumer失敗/取消→全texture解放→同renderer再試行が保持先。旧Sessionの暗黙unbind仕様は廃止対象。 |
| `unboundContextIsRejectedAndCanBeReleased` | M1 | unbound requireCurrent 拒否→close→新contextを通常GPU基盤試験へ。[FOUNDATION-CLOSE] はtexture close失敗だけなので補完が必要。 |
| `detailedAllocationPathMatchesBaselineAndCountsActualCalls` | E | [PERF] のFULL_RECOMPUTE/analysis1/pipeline1/解放と [OBSERVE] の観測非干渉が保持先。旧probe専用allocation/pass counter計測は削除。 |
| `detailedCancellationAndConsumerFailureReleaseSession` | E | [EXPORT-FAIL] にconsumer失敗/取消の解放と再試行を集約。旧detailed probeの独自計測経路は削除。 |

### [PreviewParallelDiagnosticTest](../app/src/gpuDiagnosticTest/java/dev/cinematic/p0/raw/PreviewParallelDiagnosticTest.kt) — 5件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `smallStagesMatchInBothOrders` | D | CPU Foundation/Orton/Character のworker1/2 raw hash比較と時間だけの旧probe。 |
| `cancellationAndRetry` | E | [CANCEL] でGPU取消後retryを保持。旧CPU比較probe自体の再試行は廃止。 |
| `entryDoesNotStart` | D | 旧 PreviewParallelDiagnosticActivity 起動だけでは旧probeを開始しないという廃止入口固有保証。 |
| `full1024StagesMatch` | D | 1024px CPU worker1/2比較の旧probe。GPU1024px全工程時間は [PERF] を保持。 |
| `explicitStartCanCancelAndReleaseGate` | M1 | 旧start/cancel UIは削除し、実処理終了まで共通admissionを解放しない保証を通常入力/preview取消に残す。Future取消のみで成功扱いしない。 |

## 通常側の診断参照候補 22ファイル72件

### [DevelopEngineAndroidTest](../app/src/androidTest/java/dev/cinematic/p0/gpu/DevelopEngineAndroidTest.kt) — 8件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `devicePreferenceSurvivesSettingRecreation` | D | CPU/GPU選択設定の再生成・次回選択とactive exportの分離という廃止する診断設定固有仕様。GPU保存request固定は M2 で保持。 |
| `ordinaryContextIgnoresAllDiagnosticPreferencesWithoutMigration` | M1 | 通常GPU固定・旧CPU/GPU/未知設定を読み替えず書換えない保証を通常routing試験として保持。旧設定クラス削除時もassertを残す。 |
| `unavailableCapabilityDisablesGpuAndShowsReasonThenRetryCanRecover` | E | [CONTEXT] と [EDITOR] の失敗表示/明示retryを保持。廃止するエンジン切替ボタンの能力チェックUIは削除。 |
| `checkingAndDisabledEditingNeverEnableGpu` | D | 廃止するCPU/GPU設定Viewのchecking/disabled時ボタン非活性だけの試験。製品編集/save状態は別途M2で保持。 |
| `releasePolicyHasNoSettingAndDoesNotProbeGpu` | M1 | 通常版で設定非表示・診断設定をread/writeせず診断probeを始めない保証を通常routing/entry不在の試験へ。 |
| `detachedProbeCannotOverwriteTheNewAttachment` | D | 廃止する設定Viewのattachごとのcapability probe結果更新だけの試験。製品入力の古いcallback拒否はM1へ別途移行。 |
| `recreatedSettingsViewUsesTheSharedCapabilityResult` | M1 | 共有 DevelopGpuAvailabilityCache が残るため能力結果の再利用保証を設定View非依存の通常試験へ。 |
| `contextLossShowsFailureAndRetryRecoversWithoutChangingEngine` | E | [CONTEXT] の実context loss・旧submit拒否・明示retryが保持先。ここは合成例外と診断設定Viewだけなので重複を削除。 |

### [GpuGate1Part2TimingAndroidTest](../app/src/androidTest/java/dev/cinematic/p0/gpu/GpuGate1Part2TimingAndroidTest.kt) — 1件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `measureContinuousFullRecomputeWithResourceReuse` | E | [PERF] が1024px FULL_RECOMPUTE・R7/Glow毎回・analysis1・pipeline1・全texture解放を保持。[CACHE] にbudget assert。旧Gate1の長時間/thermal計測モードは手順記録と分離して廃止。 |

### [OddCropGeometryProbeTest](../app/src/androidTest/java/dev/cinematic/p0/jpegrender/original/OddCropGeometryProbeTest.kt) — 4件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `oddCropPreservesPixelsAndOrientation` | M1 | native crop奇数原点・CFA4種・orientation1..8のpixel対応は入力解析保証。通常debugへ実行gateを移す。 |
| `evenCropAndActiveAreaBoundariesPreservePixels` | M1 | 偶数crop/ActiveArea境界・端のgeometry/pixel対応を通常debugで保持。 |
| `invalidGeometryRemainsRejected` | M1 | 範囲外/ゼロ/不正ActiveAreaのnative preflight拒否を通常debugで保持。 |
| `cancellationKeepsOriginalAndAllowsNextDecode` | M1 | native取消で原本を保ち次decodeが成功する保証を通常debugで保持。 |

### [Original24CancellationRetryInstrumentationTest](../app/src/androidTest/java/dev/cinematic/p0/jpegrender/original/Original24CancellationRetryInstrumentationTest.kt) — 8件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `diagnosticCpuSaveCancellationRequiresExplicitRetryBeforePublication` | E | 同クラス normalGpuSaveCancellationRequiresExplicitRetryBeforePublication に共通保存安全を集約。CPU producer固有witnessは廃止。旧CPU保存intentは再描画せず別互換試験を保持。 |
| `gpuSaveCancellationRequiresExplicitRetryBeforePublication` | E | 同クラス normalGpuSaveCancellationRequiresExplicitRetryBeforePublication と同じrunCaseの通常GPU分岐に集約。 |
| `diagnosticCpuSaveCancellationAfterJpegEntryRemainsHeld` | E | 同クラス normalGpuSaveCancellationAfterJpegEntryRemainsHeld にJPEG開始後HOLD/自動解除禁止を集約。旧CPU保存読取は別途維持。 |
| `gpuSaveCancellationAfterJpegEntryRemainsHeld` | E | 同クラス normalGpuSaveCancellationAfterJpegEntryRemainsHeld と同じrunCaseの通常GPU分岐に集約。 |
| `normalGpuSaveCancellationRequiresExplicitRetryBeforePublication` | M2 | 既に通常側で実行可能。移動不要・保持して最終headで再実走。取消→通常advance不変→同世代明示retry・原本/intent不変。 |
| `normalGpuSaveCancellationAfterJpegEntryRemainsHeld` | M2 | 既に通常側で実行可能。移動不要・保持して最終headで再実走。JPEG開始後HOLDを明示retryでも自動解除しない。 |
| `normalGpuConfirmedCancellationCanPublishChangedRecipeWithoutRetryingOldSave` | M2 | 既に通常側で実行可能。移動不要・保持して再実走。確認済み取消後、新recipe新世代を公開し旧世代を再試行しない #357。 |
| `normalGpuConfirmedCancellationCanChangeRecipeThroughOrdinaryUi` | M2 | 既に通常側で実行可能。移動不要・保持して再実走。通常UIで確認済み取消後のrecipe変更→新世代保存 #357。 |

### [Original24FailureInstrumentationTest](../app/src/androidTest/java/dev/cinematic/p0/jpegrender/original/Original24FailureInstrumentationTest.kt) — 6件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `partialOutputWriteNeverReturnsTrialResultOrAllowsAnotherRender` | M2 | GPU producerで実JPEG partial write後の結果/再render禁止・close確認・原本不変へ移行。 |
| `failedOutputCloseRetainsOwnerUntilThatSameCloseIsAcknowledged` | M2 | GPU producerで同じoutput closeが確認されるまでowner保持・失敗再close・再render禁止へ移行。 |
| `sameLengthDecodableReadbackChangeNeverBecomesTrialResult` | M2 | GPU producerで同長かつdecode可能なJPEG改変readbackを拒否し結果にしない保証へ移行。 |
| `stopAfterOutputStartsCannotReturnTrialResultOrRestartTheAttempt` | M2 | GPU producerで実出力開始後stop・結果なし・再render禁止・close/原本確認へ移行。 |
| `bitmapReportingFailureCannotPublishOrRestartTheAttempt` | M2 | 観測失敗でoutput開始/公開を許さない保証をGPU/JPEG観測境界へ移行。CPU bitmap stage名だけは廃止。 |
| `failedReportingCannotHideThePrivateWriteFailure` | M2 | 実write failureをprimaryとし報告失敗をsuppressedに残す保証をGPU/JPEG境界へ移行。 |

### [Original24GpuInputAndroidTest](../app/src/androidTest/java/dev/cinematic/p0/jpegrender/original/Original24GpuInputAndroidTest.kt) — 3件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `inputOnlyEntryMatchesCpuSessionAsShotWithoutPreparingR7` | M1 | CPU sessionとのbits比較を削除。AS_SHOTだけを読みR7未解析・寸法・owner解放・原本不変を通常入力へ残す。 |
| `cancelledInputEntryIsTerminalAndRetainsNoTransientFiles` | M1 | GPU入力取消はterminal、再利用拒否、transient file/ownerなし、原本不変を通常debugへ。 |
| `gpuInputReducesOriginalOnceToPixelLimitedDimensions` | M1 | nativeRGB16→1回BOX縮小の独立oracle、1152²→886²/1024×769→1023×768を保持。CPU look比較ではなく入力仕様の検証。 |

### [Original24GpuOutputAndroidTest](../app/src/androidTest/java/dev/cinematic/p0/jpegrender/original/Original24GpuOutputAndroidTest.kt) — 3件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `actualRendererRowsArePassedUnmodifiedForNeutralAndWarmExposure` | M2 | 実GPU rowsを変更せず順序通りに別GL threadで渡す保証を通常debugへ。 |
| `consumerFailureDrainsAndIsRetryableOnlyAfterClose` | M2 | consumer失敗の理由/cleanup確認とowner解放を通常debugへ。 |
| `interruptionDrainsBeforeReturningAndNeverAuthorizesGpuRetry` | M2 | caller interrupt後も実drain完了まで返らずGPU retryを許可しない保証を通常debugへ。 |

### [Original24GpuSafetyInstrumentationTest](../app/src/androidTest/java/dev/cinematic/p0/jpegrender/original/Original24GpuSafetyInstrumentationTest.kt) — 3件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `pseudoContextLossRequiresExplicitRetryAndKeepsFrozenGpuAt12Mp` | M2 | 実12MP context loss→intent不変/通常advanceで再生成なし→同世代明示retry/失敗数1を通常側へ。CPU選択切替部分は旧prefs注入へ置換。 |
| `fiveConsecutiveGpuWritesUseNewGenerationsAndReleaseEachOwnerAt12Mp` | M2 | 実12MP×5回、各新世代/別recipe/別URI、各owner解放、失敗数不変を通常側へ。1回保存に縮小しない。 |
| `optIn24MpGpuWritePublishesOriginalSizeAndReleasesOwners` | M2 | opt-in実24MP GPU公開寸法/原本不変/owner解放を通常側へ。専用引数付き実走を別記しskipを合格にしない。 |

### [Original24GpuSaveInstrumentationTest](../app/src/androidTest/java/dev/cinematic/p0/jpegrender/original/Original24GpuSaveInstrumentationTest.kt) — 1件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `gpuSaveKeepsStartSelectionAndReconcilesOneVerifiedPublicJpeg` | M2 | 実GPU witness、凍結source/recipe、AS_SHOT由来EV、回転/sRGB実JPEG、journal照合の冪等性を通常側へ。診断CPU切替は旧prefs注入へ置換。 |

### [Original24ManualSaveInstrumentationTest](../app/src/androidTest/java/dev/cinematic/p0/jpegrender/original/Original24ManualSaveInstrumentationTest.kt) — 1件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `orientedSyntheticDngPreviewAndExplicitSaveReconcileOneVerifiedPublicJpeg` | M2 | CPU bitmap/比較bitmap所有権は廃止。preview準備が公開しない、oriented原本・明示保存・単一journal/URIをGPU preview/saveへ移す。 |

### [Original24PipelineTest](../app/src/androidTest/java/dev/cinematic/p0/jpegrender/original/Original24PipelineTest.kt) — 1件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `dngToR7LookOriginalJpegAndSameFileReadback` | M2 | 旧CPU probeの時間・内部stageは削除。strength0/非0の実JPEG、orientation1/6、同一ファイルreadback fingerprint/原本/全owner解放をGPU private保存試験へ。 |

### [Original24PreflightInstrumentationTest](../app/src/androidTest/java/dev/cinematic/p0/jpegrender/original/Original24PreflightInstrumentationTest.kt) — 5件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `existingNativeParserReportsExpectedRotationWithoutMovingBorrowedDescriptor` | M1 | native parser rotation/寸法・借用FD offset不変を通常debugへ。 |
| `existingNativeParserRejectsCyclesAmbiguousRawIfdsAndDuplicateCriticalTags` | M1 | cycle/複数RAW IFD/critical tag重複拒否を通常debugへ。 |
| `integralDefaultCropReportsAbsoluteOffsetsAlongWithOutputDimensions` | M1 | 整数cropの絶対offsetと出力寸法を通常debugへ。 |
| `existingNativeParserRejectsFractionalCropNonunitScaleAndUnknownUserCrop` | M1 | 分数crop/nonunit scale/未知user crop/範囲外拒否を通常debugへ。 |
| `cancellationAndNonclassicTiffAreRejectedBeforeOwnerAdmission` | M1 | 取消/非classic TIFFのowner admission前拒否を通常debugへ。 |

### [Original24PreviewSessionInstrumentationTest](../app/src/androidTest/java/dev/cinematic/p0/jpegrender/original/Original24PreviewSessionInstrumentationTest.kt) — 11件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `over1024SmoothLightDngPreviewAndActualJpegKeepManualRecipe` | M2 | CPU previewと縮小JPEGの数値近似閾値は削除。手動recipe/EV・原寸保存/preview寸法・原本/owner安全をGPU保存へ移す。 |
| `comparisonIsIndependentAlignedAndUnaffectedByManualControls` | E | [EDITOR] の比較操作と [INPUT] のAS_SHOT独立入力/原本保持へ集約。CPU comparison Bitmapの別allocation/色空間仕様は廃止。 |
| `comparisonAllocationOomPreservesVerifiedAfterAndReusableSession` | D | 廃止する任意CPU comparison Bitmap allocation OOMでCPU After Bitmapを保つ仕様。GPU textureはこのallocationを使用しない。 |
| `optionalComparisonOomDoesNotMaskSourceCancelCloseAdmissionOrIoFailure` | M1 | 任意比較のOOMがsource/取消/close/admission/I/O失敗を隠さない優先順位をGPU入力/比較のfault試験へ。CPU Bitmap生成自体は削除。 |
| `independentlyOwnedPreviewReusesReducedInputAndAppliesAllThreeControls` | E | [CACHE] の調整ごとの再計算/再利用と [INPUT] の原本/owner保証に集約。CPU fields/Bitmap cache数値は廃止。 |
| `sourceChangeFailsBeforeReuseAndNeverRestartsFailedSession` | M1 | source変更をreuse前に拒否、失敗sessionを再利用しない保証をGPU入力へ。 |
| `failedSourceCloseRemainsOwnedAndCloseRetriesExactStream` | M1 | source close失敗時は同じstreamを所有しclose確認まで保持する保証をGPU入力へ。 |
| `independentDecodeRejectsSameSizeCropAndOrientationMismatch` | M1 | 同寸法でもcrop原点/orientation不一致をnative decode後に拒否する保証をGPU入力へ。 |
| `draftReusesExactDecisionsAndReleaseMatchesFreshExactPixels` | E | [CACHE] がGPU温度/EV/強度cached-fresh一致を保持。CPU256pxdraft/1024exact decisionsの共有仕様は廃止。 |
| `draftDefersSourceFailureUntilExactWhileCancellationStillClearsAllDecisions` | M1 | CPU draft遅延検証仕様は削除。source変更/取消後に入力ownerを解放しclosed sessionを拒否する保証をGPU入力へ。 |
| `metadataFencesAvoidWarmSourceReadsAndKeepPixelsAndStrongColdBoundary` | M1 | warm metadata fenceとcold強いhash境界をGPU入力へ。metadata同一の原本変更でもcold保存境界で必ず拒否、失敗後再利用禁止を保持。 |

### [OriginalBloomFieldsBenchmarkTest](../app/src/androidTest/java/dev/cinematic/p0/jpegrender/original/OriginalBloomFieldsBenchmarkTest.kt) — 1件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `sequentialAndBatchedBloomFieldsAlternateOnIdenticalStreamingFixture` | D | CPU逐次/batched Bloom reduced fieldsのchecksum/時間比較だけ。元計測記録は消さない。 |

### [OriginalFieldRowBenchmarkTest](../app/src/androidTest/java/dev/cinematic/p0/jpegrender/original/OriginalFieldRowBenchmarkTest.kt) — 1件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `originalToneFieldThreeWalksAlternateLegacyAndCachedRows` | D | 旧CPU tone field row cacheの3walk/96KB payload/時間比較だけ。共有OriginalRasterFile自体の削除許可ではない。 |

### [OriginalMaskCountBenchmarkTest](../app/src/androidTest/java/dev/cinematic/p0/jpegrender/original/OriginalMaskCountBenchmarkTest.kt) — 1件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `compareRgbReadAndMaskOnlyOnFixedSyntheticFloatRasters` | D | 旧CPU mask countのRGB読込/読込なし時間比較だけ。生成済み元資産/計測記録は消さない。 |

### [OriginalOrtonParallelSafetyTest](../app/src/androidTest/java/dev/cinematic/p0/jpegrender/original/OriginalOrtonParallelSafetyTest.kt) — 8件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `cancellationStopsQueuedWorkersAndAllowsCleanNewAttempt` | E | [BOUNDARY] の取消→owner解放/原本不変→同世代GPU再試行で保持。CPU queued workerの本数固有assertは廃止。 |
| `oneWorkerExceptionPropagatesAndSiblingsStop` | M2 | 失敗primary伝播・他作業停止・原本不変・次attempt可能をGPU producer故障試験へ。CPU siblingの実装固有assertは廃止。 |
| `cancellationInsideOrtonPixelMathStopsSiblings` | E | [CANCEL] の実GPU境界取消/全texture解放で保持。CPU pixel内poll回数は廃止。原本付き保証は [BOUNDARY]。 |
| `faceCutRerenderMatchesSingleWorkerRawBits` | D | 旧CPU face-cutのworker1/4 raw bits比較。GPUの数式/画像基準は既存goldenを維持。 |
| `largeDimensionsCancelAndRemovePartialRaster` | M2 | 共有OriginalRasterWorkspaceのpartial file所有/worker終了/確認後disposeを通常試験へ残す。OriginalRasterFileは変更せず、残存利用を削除前に照合。 |
| `actualAttemptCancellationClosesOwnersAndProtectsDng` | E | [BOUNDARY] の実producer取消・原本不変・private outputなし・owner解放/再試行に集約。旧CPU Orton注入点は廃止。 |
| `simultaneousPrivateSessionsHaveIdenticalOutputAndNoSharedWorkers` | M2 | CPU出力hash比較は削除。独立入力/ownerが同時処理で混ざらない保証はA保存+B入力の通常GPU試験へ集約し実走。 |
| `synthetic24MpAttemptCancellationAtOrtonClosesResources` | M2 | 24MP取消の原本/partial output/全owner解放を実GPU24MPへ移す。小fixtureの成功で代替しない。 |

### [OriginalR7ColorFusionAndroidTest](../app/src/androidTest/java/dev/cinematic/p0/jpegrender/original/OriginalR7ColorFusionAndroidTest.kt) — 2件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `separateAndFusedPassesProduceIdenticalJpegAndReleaseOwners` | E | [GPU-SAVE] の実GPU JPEG寸法/同一readback/原本/owner保証を保持。CPU fused/separate完全JPEG一致と時間は廃止。 |
| `fusedWorkerCancellationAndFailureCloseAttemptOnEmulator` | M2 | CPU fused worker固有注入は廃止。取消/失敗primary・未公開・原本/owner解放をGPU保存故障群へ統合。 |

### [OriginalRemainingRowsAndroidTest](../app/src/androidTest/java/dev/cinematic/p0/jpegrender/original/OriginalRemainingRowsAndroidTest.kt) — 1件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `sameApkParityTimingMemoryAndInterruptedCleanup` | M2 | CPU複数armのhash/速度比較は削除。共有rasterのI/O/worker drain完了・原本不変・中断cleanupの保証を通常保存/共有raster試験へ。 |

### [OriginalSaveParallelParityInstrumentationTest](../app/src/androidTest/java/dev/cinematic/p0/jpegrender/original/OriginalSaveParallelParityInstrumentationTest.kt) — 1件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `threeWorkerSettingsKeepJpegStageOrderPublicationAndJournalAcrossRetries` | M2 | CPU worker数/JPEG数値比較は削除。intent→render close→publisher、journal全revision、insert/copy/publish前phase、retry無再生成/無二重公開をGPUに移す。 |

### [PreviewCacheEquivalenceAndroidTest](../app/src/androidTest/java/dev/cinematic/p0/photoroll/PreviewCacheEquivalenceAndroidTest.kt) — 1件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `retainedInputAndBaseMatchColdSessionsForAllControls` | E | [CACHE] のGPU cached/fresh bit一致・調整無効化・budget/解放、[INPUT] の原本保持に集約。CPU8bit cache比較は廃止。 |

### [PreviewRawGestureFixtureAndroidTest](../app/src/androidTest/java/dev/cinematic/p0/photoroll/PreviewRawGestureFixtureAndroidTest.kt) — 1件

| method | 分類/移行PR | 保持する保証・削除する部分・対応先 |
|---|---|---|
| `dragAndReleaseReachRealSyntheticRawPreview` | M1 | CPU exact/draft worker切替UIは削除。実RAW上のdrag/release・最終recipe描画・readonly provider/idle復帰を通常GPU gesture試験に残す。 |

## 契約差2件の本人承認と代替保証

本人の「提案の方針でOKです」を親担当が候補Aへの承認として確認。以下は製品仕様を変えず、
`GpuInputLifecycleSafetyAndroidTest` に集約する。上表の「判断待ち」はこの承認で更新されるが、
追加assertは実走未完。旧診断試験はまだ削除しない。

| 終了候補の旧保証 | 通常側の代替保証 | 実走・終了条件 |
|---|---|---|
| 遅延UI_HIDDENでも新loadを継続 | preflightをbarrierで保持してtrimを通知。drainと旧owner終了、原本不変、暗黙再開なし。明示retryで新sessionだけが読み込み・表示 | 追加assert未実走。通常側成功確認後、削除①で旧継続assertを終了 |
| 実描画まで旧error/retryラベル保持 | 明示retryの初frameをbarrierで保持。CHECKING表示・保存不可・旧owner終了。実frame後のみ保存可能、再度のGPU失敗は問題表示へ | 追加assert未実走。通常側成功確認後、削除①で旧ラベル保持assertを終了 |

部分2件は別扱いを維持する。releasePolicyの旧View依存保証は削除①の入口/定義不在確認で置換し、
warm metadata/cold保存のSHA保証は移行②の実GPU保存前後改変試験を実走して完了させる。

## 移行②の先行実装（未実走）

M1/#408 の試験側lint修正head `5d1e36e8` に依存する別branch/worktreeで準備。製品hook/共有raster/sourceSetsは変更しない。
M2の49元メソッド中14件について通常側の対応を実装、4件は既存通常取消試験を保持して再実走予定、残る31件は未実装。
さらにM1のcold保存部分を1method追加したため、この先行実装の通常Android選択予定は **15 method**。
49件を15件だけで完了扱いにしない。移行②は同じPRへ保存/所有権/FGS/実12MP×5/24MPを追加する。

| 移行元 | 通常側 | 予定method数 | 保持・変更範囲 |
|---|---|---:|---|
| Original24GpuObserverFailureAndroidTest 5件 | GpuSaveObserverSafetyAndroidTest | 5 | 全assert保持。失敗primary/suppressed/self-suppression、cleanup不明はHOLDを維持。ledger/thread終了と保守的held ownerを区別。旧診断gateを通常debug/明示引数のfail-fastへ |
| Original24GpuOutputAndroidTest 3件 | 同名class | 3 | 実GPU row順序・neutral/warm値、consumer失敗、interrupt/drain/retry禁止を保持。通常gateへ変更 |
| Original24FailureInstrumentationTest 6件 | GpuSaveProducerSafetyAndroidTest | 6 | CPU producerを明示GPU producerへ置換。実JPEG partial write/同stream close保持/同長decodable改変/出力開始後取消/報告失敗の全assertを保持。BITMAP_PACKは現GPU JPEG橋渡しにも存在するため、GPU rows packing後の報告境界として保持 |
| M1 metadataFencesのcold保存部分 | GpuSaveProducerSafetyAndroidTest.unchangedMetadataCannotBypassColdSaveFingerprintBeforeOrAfterGpuJpeg | 1 | size/mtime同一でも前後SHA拒否。変更対象は試験が作った私有DNGのみ。テスト終了時に復元して原本hash確認 |

通常gateは `isolated=true` / `gpuSaveSafety=true`、debug package、emulator限定。欠落はskipでなく失敗。
observer試験は実GL資源の失敗を偽装するものではなく、報告例外/不明cleanup証拠の合成注入である。
旧診断側・CPU試験は対応する通常試験が実走するまで削除しない。AVDは未予約/未使用。

### 移行② dot 追加分（コンパイル済み・Android未実走）

`Original24PipelineTest.dngToR7LookOriginalJpegAndSameFileReadback` の GPU 安全保証を
`GpuSaveReadbackSafetyAndroidTest.bothOrientationsAndStrengthsKeepSameFileProofOriginalAndReleasedOwners`
へ追加。orientation 1/6、strength 0/.5、実 GPU JPEG の寸法と同じファイルの fingerprint/readback、
原本不変・全 attempt owner 解放を保持。CPU stage timing/worker設定は移さない。
合成 64x48 のみで、12MP/24MP 実寸保証の代わりにはしない。旧試験は残す。
直接 `:app:compileDebugAndroidTestKotlin` は成功（31秒）。Android 実走は未実施のため完了扱いしない。
先行の SDK 未指定/不完全な D ドライブ SDK 指定による構成失敗後、既存の完全な C ドライブ SDK で成功した。
この追加で M2 対応実装は15元件、既存保持4、未実装30。選択予定は16 method（M1 cold境界1を含む）。

### 共有raster取消の通常移行（dot・コンパイル済み）

`OriginalOrtonParallelSafetyTest.largeDimensionsCancelAndRemovePartialRaster` の共有資源保証を
`SharedRasterCancellationSafetyAndroidTest.fullSizePartialRasterRemainsOwnedUntilWorkersDrainAndExplicitDispose`
へ追加。6000x4000 の行入力、実 partial 書込、worker終了、close確認後disposeを検証する。
CPU look/Orton画像比較は行わず、共有raster製品実装と旧試験は変更しない。
`:app:compileDebugAndroidTestKotlin` 成功35秒。Android未実走なので削除条件成立とは扱わない。
M2対応実装16元件、既存保持4、未実装29。選択予定17 method（M1 cold境界1を含む）。

### 保存journal全revisionの通常GPU移行（dot・未実走）

`OriginalSaveParallelParityInstrumentationTest.threeWorkerSettingsKeepJpegStageOrderPublicationAndJournalAcrossRetries`
を `GpuSaveJournalSafetyAndroidTest.gpuSaveKeepsStageOrderPublicationAndEveryJournalRevisionAcrossRetries` へ対応。
CPU worker arm比較だけを除き、実GPU producer、回転/調整の2条件、intent先行・render close後publisher、
insert/copy/publish直前phase、7phase/revision0..6、recipe/source/generation、永続codecの一致、
公開後2回advanceでrender/publisher各1・events/journal bytes不変・原本保持を残す。
所有合成MediaStore行だけを成功時にowner/name/pending状態照合して片付け、失敗時証拠は残す。
通常Androidコンパイル成功27秒。実走は未実施、12MP/24MPやFGSの代替ではない。
現時点M2対応実装17元件、既存保持4、未実装28。選択予定18method（M1 cold境界1を含む）。
#408マージ後のmainを通常mergeで同期し、M1試験はmainの検証済み内容を保持した。
