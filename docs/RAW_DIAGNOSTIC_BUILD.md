# RAW実機診断

関連: #78 / #81 / #101、比較候補 #185（head `8ef8013`）、診断入口の統合 #196。
診断の比較結果と通常製品の保存経路の検証は別に扱う。

## build条件

`-PcinematicRawDiagnostic=true`でのみ`rawDiagnostic`を作る。AGPのrelease継承、
application ID `dev.cinematic.p0.rawdiagnostic`、非debuggable / 非JNI-debuggable、profileable shell=true、
R8/resource shrink=false、CMake RelWithDebInfo（生成flagsのO2/NDEBUGを両ABIで検証）、
min/target SDK 28/37、arm64-v8a / x86_64、既存debug signing config、largeHeapなし。
固定署名modeおよびoriginal24Probeとの併用は拒否する。通常debug/releaseに診断Activityは含まれない。

```
./gradlew :app:lintRawDiagnostic :app:testRawDiagnosticUnitTest :app:assembleRawDiagnostic \
  -PcinematicRawDiagnostic=true -Pandroid.onlyEnableUnitTestForTheTestedBuildType=false --no-daemon
```

Windowsではgradlew.batを使う。source/TEMP/buildを専用作業領域に置き、共有Gradle/GMDの稼働と調整する。
APKは`app/build/outputs/apk/rawDiagnostic/app-rawDiagnostic.apk`。CIは通常manifestへのprofileable漏出、
APK ID/debuggable/heap、native flagsも検査する。診断APKの配布artifactは作らない。
診断の要求permission 0、入口Activity 1、service/provider/receiver/aliasなしもAPKで検査する。
共有mainに残る到達不能CineRecorderの録音MissingPermissionだけを専用lint.xmlで除外する。
この設定は上記の診断専用lintコマンド（許可task集合）のみ適用し、通常debug/release、aggregate、混在lintでは無効。
製品の権限処理や正常CIを弱めず、到達不能はmanifestとsynthetic smokeで確認する。

旧Pixel source `3bbd9548364b36d092ec064f39b66bbdeceac673`、tree `928fe3d11b69e75e0541a2c07b49f0535b526ac0`の
realPhotoProbe recipeと保存済みmanifest/native監査もrelease継承、非debuggable、profileable、R8無効、O2だった。
これはbuild設定の一致であり、package/署名/ART/install/cache/入力/計測境界の一致ではない。
旧35秒と同条件と称さず、旧結果から今回の改善率を算出しない。旧200ms samplerも移植していない。

## 本人による入口

専用packageのlauncherからFilesで本人が許可したDNGを選択する。読取専用の一時URI grantを使い、
永続grantや写真名/EXIF照会をしない。camera/FGS/通常画面/要求permissionを専用manifestから除外する。
64MiB上限の私有入力copyを作り、各試行は新しいOriginal24Attemptとscratchを使う。
製品のsave coordinator/journal/MediaStoreを通らず、既存保存結果の再利用を反復に数えない。
入力copy/未完JPEG/失敗scratchは解放確認後に削除、成功JPEGは各試行後に削除する。数値reportだけ私有領域に残す。回収未確認はHOLD。
選択元のbytesを最後に再照合する。試行のsourceUnchangedは私有入力、selectedOriginalUnchangedは選択元の検査を指す。

APK導入、Files選択、比較条件/測定modeの選択、開始、取消、結果共有は本人の操作として別途案内する。
ビルド成功は写真利用・インストール・端末操作の許可を意味しない。署名不一致をapp削除で回避しない。

## 比較と反復

- R7 Foundation: 旧baseline 1 workerと2 worker。empty ROIのみ並列、外部ROI callbackはserial。
  通常原寸手動保存のcallerは2を明示する。Original24Attempt/OriginalStageClear24のAPI既定は1を維持し、
  この診断も製品callerを使わず各armの1/2を明示する。tone gain onceの過去変更とは別の比較。
- Glow: 1対2、または1対4を選択。light/diffusionの2つの行合成だけ並列化し、間のglobal blur境界を保持。
  worker専有行buffer、既存ordered executorとowner管理を使う。通常既定1を変えない。
- 一つのsessionはwarmup AB、その後ABBAを2巡（逆順はBA/BAABを2巡）。各arm初回除外後ちょうど4成功、計10回。
  失敗/取消/不一致があれば停止し、不足分を成功扱いせず、少数試行からP95や確定的改善率を出さない。
- 同じsession内の入力・設定は固定。strength=.5、warmth/追加EV=0、Orton4/Character2。
  旧Foundation/Glow比較は比較対象以外のFoundation/Glowが1。RAW4とR7追加比較はFoundation2を共通基準にする。最初の成功JPEGを基準に全試行のfingerprint一致をboolだけ残す。
  hash値・写真bytes・写真名・URI・撮影情報・端末serial・例外本文/stackをreportへ出さない。
- session開始前にsource commit/APK hash/ABI/build条件を別の検証記録に残す。入力対応表は公開しない。
  同じ端末状態・明るさ・給電・設定で順序を交替し、各sessionを冷却後に開始する。
  通常アプリの現像/保存や他の重い背景処理と重ねない。診断gateは別packageの処理までは排他しない。

## 時間・メモリ・環境

時間modeはOriginal24Attempt.renderとcloseの外側をSystem.nanoTimeで測る。
入力import、report書込、JPEG削除、最後の選択元照合は外側にあり、製品の共有保存全体ではない。
工程は既存OriginalStageTrace。ORTON親に含まれるFIELD/COMPOSEとthread CPUを総和から除外し、
外側elapsedからtop-level工程和を引いた値を未分解残差とする。CPU/GC/IOへ推測配賦しない。
clock/record observer自身の費用は完全には除去できない。通常経路はtrace=nullでclock/event収集しない。

メモリmodeは別session。100ms目標の周期samplerでmanaged使用量とnative allocated bytesを観測する。
PSS KiBはsamplerの外でbefore/afterのみ。samplerは定数領域の集計とし、実間隔、最大gap、欠測、収集費用を残す。
工程内部の瞬間peakを保証せず「観測最大」と呼ぶ。メモリmodeから時間改善を主張せず、各項目の最大値を足して同時peakとしない。
停止/join未確認ならowner HOLDで次試行を禁止する。dumpsysの高速ポーリングは行わない。

各試行の前後にthermal status、電池温度（0.1℃単位）、battery status/充電状態を記録。
thermal API未対応/欠測は-1、電池温度欠測は-1、充電不明はnullとして解釈する。
開始前thermal MODERATE以上またはlowMemoryで停止する。試行中の連続thermal監視ではない。
OS警告・熱・資源不足では本人が取消し、CPU/thermal強制設定や上限拡大で継続しない。

## lifecycleと未検証範囲

Back/回転/背景でcooperative cancel、native CancellationSignalも取消す。自動再開しない。
process-wide gateで重複開始を防ぎ、owner解放不明/Throwable時はHOLDして新しい試行を許可しない。
閉じた画面へのreport復元は未実装。process deathは完了とみなさず、途中私有report/scratchが残り得る。

JVMのraster一致/取消試験、APK build、synthetic Android実行、本人実機JPEG一致・性能・資源・見た目確認は別の証拠。
この入口は出力JPEGを削除するため目視画質確認や製品Save/FGS復帰試験の代わりにならない。
24MP成功を全DNGメーカー互換やメモリ安全保証へ一般化しない。

## 旧診断入口の合成検証の証拠（RAW4統合前）

- 診断variantのlint・全JVM unit・assemble、およびrelease manifest生成に成功。879 tests、failure/error 0、既存skip 4。
- 通常debugのlint・全JVM unit・assembleにも成功。874 tests、failure/error 0、既存skip 4。debug/releaseのmanifestに診断入口/profileableがないことを確認。
- Foundation/Glowのfocused JVM 13 testsに成功。全stage/final raster bit一致、取消/例外時owner解放を含む。
- 専用ATDのrunner 2 testsとUI/lifecycle 1 testを分離実行し、failure/skip 0。全3比較の各8 fresh attempts、
  JPEG一致・原本不変・私有入力/JPEG削除、reverse/memory分離、permission 0・永続grantなしを確認。
- UIはpickerのIntent/結果受渡し・取消、二重開始防止、背景lifecycle遷移/Activity再生成による取消と再開始を確認。
  外部Files画面、OSの実URI grant強制、物理回転表示は未検証。実写真/Pixelの性能・資源・目視品質は未実施。
- APK自体の署名・ID・非debuggable/profileable・権限0・唯一の診断入口、および両ABIのO2/NDEBUGを確認。
  数値や合成結果を実機12/24MPの改善率へ読み替えない。共有枠の使用/解放と限定失敗の説明はIssue #78に記録する。
公式根拠: [build variants](https://developer.android.com/build/build-variants)、
[profileable](https://developer.android.com/guide/topics/manifest/profileable-element)、
[SAF](https://developer.android.com/training/data-storage/shared/documents-files)、
[CMake build type](https://cmake.org/cmake/help/latest/variable/CMAKE_BUILD_TYPE.html)。

## RAW4一括診断（Issue #78、製品既定は変更しない）

要件: 同じ入力・同じAPK・Foundation2/Orton4/Character2固定で、AはGlow1/Mist1/tone1/色融合off、
BはGlow2/Mist2/tone2/色融合on。sRGB近似は対象外。全JPEG厳密一致・既存golden・取消とowner契約を維持する。
1 sessionはwarmup AB後ABBAを2巡、逆順はwarmup BA後BAABを2巡。計10回、測定各arm4回。
時間とメモリは別session。時間は既存traceの同じ境界で各試行内の反復stageを合計してから中央値を取る。
Glow/Mistはfield生成を含む全工程、toneは適用、色は旧3段合計対融合1段。全体と残差も併記する。

性能判定は正逆両方で工程中央値10%以上短縮なら採用候補、両方未満なら今回条件で見送り。
順序で10%判定が食い違う場合とR7対象区間が両順10%以上短縮した場合だけ追加比較する。R7 toneと色融合は別に切り分ける。
10%は事前に決めた実務上の性能基準であり統計的有意差や機能安全の代替ではない。
品質/原本/取消/owner gate不成立や測定未完は性能採否に変換しない。見送りは条件と結果を一覧に残す。

設計: 既存診断variant/runner/UI/私有reportを再利用し、明示profileを追加する。正逆sessionは別開始とし、
冷却・端末状態・同一入力/APKの確認後に比較する。入力の名前/URI/hash値/写真/例外本文をreportへ出さない。
100msのmanaged/native観測はメモリsessionだけ。PSSは周期samplerと分離した前後観測。
観測最大は保証peakではなく、実際の間隔・sample数・収集費用と失敗を記録する。無制限bufferは使わない。
samplerの終了未確認でも既存gateを保持し次の試行を開始しない。製品save/FGS/撮影復帰は別検証。

検証計画: profile/順序/中央値/閾値/欠測/取消/ownerのJVM試験、4候補同時onのFloat/JPEG一致と
診断UI/lifecycle合成試験、通常と診断lint/unit/build、公開前差分検査と独立レビュー。
実機性能・実写真品質・写真利用/インストールはこの実装/合成検証に含めない。
### 旧RAW4の少回数メモリと取消確認

時間は正順10回＋逆順10回（合計20回）。メモリは別の1 sessionでA/B各1回、合計2回に限定する。
取消は専用ボタンからRAW4 onを2回だけ実行し、native decode完了後とR7 Foundation完了後に協調取消する。
この境界取消はnative処理内部の任意時点の取消試験を代替しない。終了owner、部分JPEGなし、完了結果なし、
原本不変を記録し、性能測定の成功件数に含めない。失敗scratchも全owner解放を確認して回収する。削除失敗や所有不明はHOLDにし、原本や公開の記録は削除しない。
本人が途中で取消した場合は診断成功に補完せず停止する。24MPを自動選択/先行測定しない。

所要時間は同じ診断APK・同じ入力で観測した1回時間をTとすると、時間20T＋メモリ2回分＋取消2回の到達/回収時間。
これにFiles選択/入力照合/冷却が加わる。別buildの旧35秒を代入した実機完了時刻は約束しない。

正逆の私有reportは tools/raw-diagnostic/compare_raw4.py で集計する。入力識別情報はreportにないため、
同じ原本bytes/APKと端末状態を本人側で照合し --same-input-build-confirmed を明示する。
10%未満を見送りとして記録し、閾値不一致またはR7対象区間が両順10%以上短縮した時だけ追加比較する。
詳細と採否の固定labelは tools/raw-diagnostic/README.md を参照。性能採用labelは製品既定変更や安全合格を許可しない。

RAW4の合成検証結果と枠解放は[Issue #78](https://github.com/reitojike/cinematic-camera/issues/78#issuecomment-6709899746)を参照。
通常debug lint/unit/両ABI buildも成功（907 tests、failure/error0、既存skip4）。
当日の12MP手順・所要目安・#208録画準備は[RAW4_DEVICE_CHECKLIST](RAW4_DEVICE_CHECKLIST.md)を参照。

## 保存採用候補の安全確認profile（#261）

速度採否で残ったGlow / Mist / R7 toneの2並列だけを `SAVE_SAFETY` として分離する。
Aは3工程とも1、Bは3工程とも2。**色融合は両方OFF**。Foundation2 / Orton4 / Character2は固定。
通常保存caller `AndroidManualOriginalTrialSaves.Backend.render` もFoundation2 / Orton4 / Character2を共通とし、保存3候補の採用後はGlow2 / Mist2 / tone2を明示する。A/Bは過去と同じ比較構成を保ち、診断profileから製品の設定を切り替えない。
下位APIの既定1や旧RAW4 Bの色融合ONを変更しない。製品既定・画素式・原本・共有保存は変更しない。

画面上部の専用ボタンを使い、下のlegacy controlsと分離する。

- `Save safety: Memory A/B (2 attempts)`: Memory ON / Reverse OFF相当を固定し、warmupなしでAB各1回。
  `comparison=SAVE_SAFETY`、`mode=MEMORY_PERIODIC_NOT_TIMING`、`order=AB`。
- `Save safety: 2 cancellation checks`: 同じB構成でnative decode後 / Foundation後の各1回、計2試行。
  `comparison=SAVE_SAFETY`、`mode=CANCELLATION_BOUNDARIES_NOT_TIMING`、`order=BB`。
- 両入口はlegacyの比較選択・Memory・Reverseを使わない。安全profileの速度modeはrunnerでも拒否する。
  `schema=2`、既存のworker数・色融合・品質/原本/回収・観測項目を維持し、旧RAW4と比較名で区別する。
- 取消reportの固定 `cancellationScope=AFTER_NATIVE_DECODE_AND_FOUNDATION_NOT_WORKER_IN_FLIGHT` と
  各runの `cancelBoundary` で範囲を明示する。Foundation後はtone完了後・Glow/Mist開始前なので、
  3工程の並列worker中の取消やnative内部の任意時点取消の証拠ではない。既存の境界を増やさない。
- 旧RAW4の速度20回・profile・schema・集計器は互換のまま残す。新profileを旧集計器へ渡したり、
  過去reportを新候補の証拠として読み替えたりしない。充電状態は記録するが、その差を理由に速度20回を再実行しない。

当日の手順は [保存安全確認](RAW4_DEVICE_CHECKLIST.md#4-保存候補のメモリと取消だけを確認する) を参照。
この準備と合成試験は実機メモリ・取消・目視品質や製品Save/FGS復旧の合格ではない。

### 手動Cancel受付から停止・解放までの観測

`Save safety: Manual Cancel A / GLOW`、`B / GLOW`、`A / MIST`、`B / MIST` の4つの専用ボタンで、構成と狙った工程を開始前に固定し各1回だけ走らせる（#273）。Memory samplerと速度反復は使わない。
AはGlow1/Mist1/tone1、Bは2/2/2。両方で色融合OFF、Foundation2/Orton4/Character2固定。選択はFilesから戻るまで保持し、legacy選択から継承しない。
開始後は画面下部の現在工程とCancelを見える位置にし、`Running GLOW` または `Running MIST` を見てCancelを押す。
GLOW/MISTを分けて同じ入力・APK・設定・端末状態でA/Bを比べる。順序と回数は当日手順に従い、単発1対だけで非劣性を確定しない。工程を取り逃した回を成功へ補完せず、際限なくやり直さない。
既存2境界の自動取消は維持し、新しい取消checkpoint・行内の取消判定・worker中断方式は追加しない。

開始通知は既存 `OriginalStageTrace` の診断callbackだけ。trace=nullの製品経路は時計/通知を実行しない。
`stageAtRequest` はUIイベントハンドラーで最初の取消要求を受理した時点の**観測上の工程区間**であり、
OSの物理タッチ時刻・画面提示時刻・特定workerの画素命令の実行時刻ではない。UIの工程表示は遅れる場合があるのでreportを正とする。
開始callbackは処理actionの直前、終了callbackはactionの退出後に通知する。工程内のどのblur/行workerにいたかは分解しない。

私有reportは `comparison=SAVE_SAFETY`、`mode=MANUAL_CANCELLATION_LATENCY_NOT_TIMING`、`order=A` または `B`。
`intendedCancellationStage=GLOW/MIST` とrunのworker構成を残す。既存schema=2の追加項目で、旧B専用reportには狙い・経過がないため同条件の新証拠として扱わない。
`cancellationObservation` は次を区別する（単位ns、System.nanoTimeの差分。絶対時刻は保存しない）。

- `requestOrigin`: `USER_BUTTON` / `LIFECYCLE` / `NONE`。最初の要求を固定し、連打・後続の離脱で開始時刻を書き換えない。
- `stageAtRequest`: `GLOW` / `MIST` / `OUTSIDE_GLOW_MIST`。最後に完了した工程から実行中工程を推測しない。
- `stageElapsedAtRequestNanos`: 実受付stageの開始callbackから最初のUI受付まで。stage外・未到達・時計差不成立はnull。
  UI表示時刻ではなく、最初の受付後の工程遷移や連打で書き換えない。stage内の行/blur/worker進捗率ではない。
- `requestDuringRender` と `renderOutcome`: `NOT_ENTERED` / `SUCCESS` / `CANCELLED` / `FAILED`。
- `requestToRenderExitNanos`: renderが返却/例外退出した観測まで。render内部のfinally回収も含み、最終画素演算だけの停止時刻ではない。
- `requestToAttemptScopeExitNanos`: 既存useスコープのclose試行後まで。close失敗でownerが残っていてもこの退出時刻だけは測れるため、単独で静止成功としない。
- `requestToOwnersReleasedNanos`: attemptの `hasUnreleasedResources=false` を確認できた場合だけ値を残す。
  既存worker ownerはexecutorのawaitTermination成功まで保持し、失敗するとpending ownerを残す。worker終了不明を成功へ変えない。
- `requestToHarnessQuiescentNanos`: sampler/attempt/harness streamの解放と私有入力copy削除を確認できた場合だけ値を残す。
  reportの最終書込と画面表示は含まない。report返却前の書込失敗なら既存UIのHOLDとなり、成功を表示しない。

未到達・未解放・要求より前に終わった境界は `null` で、0秒や上限内に補完しない。
`OBSERVED_TARGET_STAGE_CANCEL` はUSER_BUTTON、狙ったGlow/Mist区間への一致、renderのCancellationException退出、owner/harness解放が揃った観測。
実受付がもう一方の工程なら `MISSED_INTENDED_STAGE`。HOLDはstage不一致より優先し、いずれも成功へ補完しない。
`NO_REQUEST` / `NON_USER_REQUEST` / `OUTSIDE_GLOW_MIST` / `RENDER_NOT_CANCELLED` / `HOLD_NOT_QUIESCENT` は別結果のまま残す。
通常完走は `status=FINISHED_WITHOUT_CANCELLATION`、中断は既存の `status=STOPPED`。どちらも `complete=false`、速度成功件数には数えない。
中断時は最終の選択原本再照合に到達しないため `selectedOriginalUnchanged=true` を捏造しない。既存2境界の原本照合を代替しない。
各runの `ownerClosed` / `noCompletedResult` / `outputAbsentAtAttemptScopeExit` と全体のprivate入力削除・resourcesQuiescentも確認する。

この計測は待ち時間の観測準備であり、短時間停止・安全なtimeout・実機合格の保証ではない。
所有者が残るHOLD、OS警告、異常発熱、低メモリでは追加の回を始めず止める。取消・失敗scratchはowner解放後に即時回収する。未解放または削除失敗ではHOLDにし、写真コピーを調査用に残さない。

### A/B取消比較の判定と限界（#273）

採否は候補なしAよりBの取消から停止・資源解放が悪くならないこと。2秒/5秒は参考記録に留める。
4区間を個別に比較し、ownerClosed/noCompletedResult/outputAbsentAtAttemptScopeExit、privateInputRemoved/resourcesQuiescentも確認する。
欠測・未解放・非取消・狙いstage不一致は比較対象の成功へ補完しない。資源HOLDでは次を開始しない。
`cancellationComparisonDecision=REQUIRES_MATCHED_CONDITIONS_NOT_AUTOMATIC_PASS` は自動採否をしない固定表示。

同じRunning表示で押しても、UI配送/描画/操作遅れとA/B速度差により内部進行位置は揃わない。
実受付stageとstageElapsedAtRequestNanosは差を発見する補助であり、同じ経過nsでも同じworker行位置の証明ではない。
同じ入力/APK/設定、狙いと実受付stage、温度/充電条件、押下までの経過を併記し、条件差・ばらつきが残れば未判定。
根拠のない経過許容幅を事後設定せず、Bの悪化が見えたら採用保留とする。少数回の非悪化は統計的な安全保証ではない。
A自体が参考値を超える場合も今回の診断でcheckpointを増やさず、取消位置の改善を別Issue候補として扱う。

### メモリ単発A/Bと旧ABの証拠を分ける（#273）

`Save safety: Memory A (1 attempt)` / `Save safety: Memory B (1 attempt)` は同じSAVE_SAFETYの片側だけを走らせる。
各開始前に同じ許可DNGを選び、同じAPK/設定と、冷却後のthermal/充電/開始memoryBeforeを照合する。
`mode=MEMORY_SINGLE_ARM_NOT_TIMING`、`order=A/B`、warmupなし、測定成功件数は選んだarmが1・他方0。
`complete=true` はその単発のみの完了であり、A/B全体の非悪化判定ではない。
100ms managed/native sampler、PSS前後、sampling欠測/null/HOLD、最終選択原本照合は既存と同じ。

別sessionではJPEG fingerprintを持ち越さないため `crossArmJpegComparison=NOT_AVAILABLE_SINGLE_ARM` を明示する。
各runの `jpegMatchesSessionBaseline=true` は最初の自己基準であり、単発間のJPEG一致の証拠にしない。
旧 `Memory A/B (2 attempts)` は同じ私有入力copyの連続ABとsession内JPEG一致/原本確認のために残す。
旧ABの熱/allocator/GC/順序差は解消されず、単発の冷却済み相対観測へ読み替えない。
同入力/APKのsession間対応は本人側で照合し、写真hash/URI/識別情報をreportへ追加しない。

冷却はGC/allocator状態を初期化しない。強制GC・force-stop・CPU/thermal設定変更は行わず、
開始値/環境と順序を記録してばらつきを評価する。条件差・欠測が残れば未判定。観測最大を真peakにしない。
`memoryComparisonDecision=REQUIRES_MATCHED_CONDITIONS_NOT_AUTOMATIC_PASS` は自動合格を出さない固定表示。
当日の順序/回数・既存ABの品質確認枠と単発メモリの相対比較枠は統合手順へ従う。

### 取消後の作業コピーと合成回帰（#301）

`Original24Attempt` は成功・取消・失敗とも、全resource ownerのclose後に既知のDNG snapshot・RGB16・Float32を削除する。削除が未確認ならownerを保持し、`close()` で再試行する。診断runnerの私有input/未完JPEGもharness解放後に回収する。
最初の診断sessionを作る前に、前のprocessが残した既知sessionの写真コピーを回収する。数値reportは残す。稼働中/close不明ownerを後続sessionが削除しない。旧reportの `privateFailedScratchRetained=true` を変更せず、新しい確認ではfalseを期待する。

`SaveCandidateCancellationTest` は合成DNGだけを使用し、既存の `OriginalStageTrace.started(MIST)` で取消を要求する。A/B各3回で停止、owner/worker/FD解放、出力未開始、最後の原本照合、scratch消去を確認する。これはMist開始直前の同じ境界での確認であり、Mist worker実行中や実機の待ち時間比較ではない。既存のnative decode後/Foundation後も3sessionで残存0を確認する。

実行は `docs/MANAGED_DEVICES.md` のATD枠を利用し、classを `dev.cinematic.p0.rawdiagnostic.SaveCandidateCancellationTest` に限定する。rawDiagnostic/testプロパティと `isolated=true` を明示する。旧S06のreportにはbyte inventoryがないため、合成再現の値を実機の残存実測として扱わない。