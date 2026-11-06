# P0 build evidence — 2026-11-01

## 原寸DNG・heap診断（Issue #143 / spec026）

- 別packageのdebug診断normal/largeHeapを用意。製品appの1024px/16MP/128MiB・half-size、JNI export、署名・保存・公開条件は維持する。
- main `ac0ddac`（#142）を基点としたlocal通常appはlint/build/test APK・両ABI成功、82 suites/597 unit、failure/error0。Windowsの既存symlink4件はskipであり合格に数えない。診断は両flavorのlint・両ABI・test APK成功。#144統合後の最終headのCI/review証拠はPRを参照する。
- 専用API37 x86_64 AVDで指定RAM4096→6144MiBを比較。Android totalMemは4,105,592,832→6,213,230,592 bytesへ増えたが、通常Runtime.maxMemoryは両方192MiB、memoryClass192/largeMemoryClass576MiBだった。以前の低いRAM指定には起動時4096MB自動補正ログがあり、指定値を実RAMとみなさない。
- 4GB AVDで通常診断はlargeHeap=false/maxHeap192MiB、比較診断は別packageでtrue/576MiBを実測。製品appへのlargeHeap採用・処理中切替ではない。
- 原寸nativeは4000×3000/6000×4000と各90度向きで通常・largeHeapとも成功（各3 JUnit）。half-size0、原寸16bit出力、回転寸法/四隅、原本SHA-256不変、キャンセル2種・64MiB/24MP上限・不正入力拒否・回復を確認。両heap条件の各出力summary/checksumは一致。通常条件の観測最大は12MPでnative約212MiB/PSS約233MiB、24MPで約418MiB/約439MiBだった。
- 現行R7/Stage Clearソースを再利用した合成1024px演算は両heapで初回を含む3回成功し、全6回のchecksum一致。通常2048pxはMANAGED_MEMORYで停止、largeHeap2048pxは3回成功（約60.1/56.9/57.3秒、観測managed最大約362MiB/PSS約348MiB）。失敗結果も当該runIdで回収し、自動再試行しない。1536pxの比較は未実施。
- 通常JNIの既存DNG/AS_SHOT/W1/上限等25件成功。read-onlyのdumpsys helperは対象不在を成功にせず、通常appの既存合成負荷中の集計回収にも成功。helperの失敗時回収・古いrun拒否・serial/写真情報除外を模擬出力でも検証した。
- 両診断APK実物の別package/largeHeap false・true、権限、LibRaw/Pillow/Colour関連notice、両ABIのJNI隔離、個人path除外を確認。Git差分の公開検査/diff checkを実施。手順は[RAW_PROBE](RAW_PROBE.md)。
- 全てdebug/合成/隔離AVDの結果でありPixel実測・配布版速度・真のピークではない。native decodeと現像演算は別工程で、最大値を合計しない。12/24MPのStage Clear→JPEG、実写の色/顔保護、実appの撮影優先・安定性、largeHeap製品採用は未確認。実機は本人操作で別途測定する。

## Result

Final source compiled successfully with CameraX **1.6.2**, AGP 9.4.1, Kotlin / Compose compiler 2.4.21, Gradle 9.8.1, Oracle JDK 25.0.4.1, Android SDK Platform 37.0 and Build Tools 36.0.0.

The installed Gradle 9.8.1 distribution was used for verification with a project-local Gradle user home. The same version's standard wrapper was generated successfully and is included in the source deliverable. Build validation needed elevated execution because the sandbox could not connect to the localhost Gradle daemon. No device installation or camera capture was executed.

Equivalent build command after SDK/JDK configuration:

```text
gradle --gradle-user-home .gradle-user-home --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:dependencies --configuration debugRuntimeClasspath
```

Final terminal result:

```text
> Task :app:assembleDebug
> Task :app:testDebugUnitTest
> Task :app:lintDebug
BUILD SUCCESSFUL in 51s
54 actionable tasks: 17 executed, 37 up-to-date
```

`build-validation.log` retains full local output (gitignored). Reports:

- `app/build/reports/tests/testDebugUnitTest/index.html`
- `app/build/test-results/testDebugUnitTest/TEST-dev.cinematic.p0.ExposureTest.xml`
- `app/build/reports/lint-results-debug.html`
- `app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt`

## Unit tests

4 tests / failures 0 / errors 0 / skipped 0:

- decimal milliseconds converted exactly to integer nanoseconds;
- inclusive ISO/exposure range endpoints;
- invalid, overflowing, sub-nanosecond and out-of-range inputs rejected without silent clamping;
- manual eligibility requires MANUAL_SENSOR, AE_OFF, required request keys and known ranges.

These tests do not emulate a camera or prove CaptureResult behavior, image quality, DNG validity or lifecycle correctness on Pixel 9a.

## Lint / warnings

`0 errors, 1 warning`: Gradle 9.8.2 is available; keep the verified 9.8.1 version for this P0. Gradle also reports deprecated build features affecting Gradle 10. No Gradle 10 compatibility claim is made. The initial Interop opt-in lint error was fixed using `androidx.annotation.OptIn`; no baseline or error suppression was added. Backups of private captures and reports are disabled for both legacy and current Android backup rules.

## Resolved CameraX artifacts

`debugRuntimeClasspath` resolves all CameraX artifacts to **1.6.2**:

```text
androidx.camera:camera-core:1.6.2
androidx.camera:camera-camera2:1.6.2
androidx.camera:camera-camera2-pipe:1.6.2
androidx.camera:camera-lifecycle:1.6.2
androidx.camera:camera-view:1.6.2
androidx.camera:camera-video:1.6.2
```

`camera-video` is a transitive dependency of the standard CameraX view stack; the app has no video use case or video feature. No CameraX 1.7 prerelease was resolved. Compilation verifies the 1.6.2 signatures used for RAW, RAW+JPEG, output capabilities, capture-result observation and Camera2CameraControl set/clear options.

## Deliverable identity

APK: `app/build/outputs/apk/debug/app-debug.apk`

SHA-256:

```text
5974DF12AA0FA47BDDF18553B25AB19DBBDC1FDF86832F27EABD85BE96864C7F
```

Standard generated `gradle/wrapper/gradle-wrapper.jar` SHA-256:

```text
3B8A25775A69158B5AD2B1D17A80A88DC7B40A352A73EB27D06C612A7CE68E98
```

The digest above identifies the initial reviewed local P0 debug APK, before the user authorized repository creation and APK distribution. No device installation or photography was performed. Pixel 9a availability could not be confirmed because `adb devices -l` failed to connect to its daemon. All device ACs remain unverified; see [P0_REPORT.md](P0_REPORT.md). Repository/CI and subsequent distribution follow [OPERATIONS.md](OPERATIONS.md); each distributed APK has its own run-specific digest and signing certificate.

## Bounded independent final review

One independent review covered RAW capability gating and save callbacks, AUTO reset, request/result observation, lifecycle/callback races/resource cleanup, and unsupported/error UI. Two concrete defects were found and fixed:

1. Rebind could label the session AUTO while previously applied Interop options remained in the reused CameraControl. Every successful bind now explicitly clears Interop options, displays a pending reset and enables capture only after the Future is accepted. A failed reset keeps capture disabled and offers AUTO or Stop/Start recovery. Sensor results still require separate device verification.
2. Selecting a Camera2-only ID could retain the previous camera's advertised output formats. Bind now clears the format set before querying the new ID and explicitly reports an unavailable CameraX ID.

No further definite defects were identified in that review pass. The final build, all 4 unit tests and lint were rerun after these changes; the result and APK digest above refer to the reviewed source. No device tests were substituted with static review.

## 共有画像保存の追加検証（2026-11-01）

新撮影を標準MediaStoreの`Pictures/Cinematic Camera/`へ保存する変更について、同じbuild / unit / lintを再実行。
`BUILD SUCCESSFUL in 55s`、53 tasks（24 executed / 29 up-to-date）。unitは**11件、failure/error/skip 0**。
既存の露出・能力4件に加え、バイト不変、JPEG/DNG MIMEとフォルダー、2件目insert失敗、stream open/write/close失敗、
2件目公開失敗のrollback、cleanup失敗の報告、空出力拒否を7件のテストで確認した。

lintはerror 0 / warning 4。内訳は既存Gradle更新案内1件と、標準`Uri.parse`をKTXへ置き換える任意提案3件。
警告を消すだけの依存追加やbaseline/suppressionは行っていない。manifestの権限はCAMERAのみ。

保存処理・callback/Stop/close・stream/pending/row cleanupの独立した1巡レビューでは、具体的不具合は見つからなかった。
Fake storeによるunit testは実MediaStore providerやGoogleフォトでの表示を証明しない。実機AC8は未確認。

ローカル候補APK（versionName `0.0.2-p0`）のSHA-256:

```text
b9f981c24c4514f5c89959b1f0288ecbdb71b0f7fb7b49d48feacfea336a52ac
```

候補の証明書SHA-256は`f644dbac318452cfbae4a0a70d3a59fd3e42ad9f43649b1b89b05c6eb08cc146`、
旧`v0.0.1-dev.1`は`6bfb144c67497aa10d26f36f8590e975d71efc4812b7b09eee8e6d899330122e`で、**署名不一致**。
この候補はそのまま上書き更新できない。今回の旧内部テスト写真の消失は利用者が許可したが、端末の削除や
インストールはこちらから行わない。CI候補はrun別のhash/署名になるため、配布時にその実体を別途検証する。
固定署名秘密の新規生成・Secrets登録・既存鍵のコピーは行っていない。

## 撮影UI US1: 状態APIと露出AUTO（2026-11-02）

`feat/003-capture-state`の候補でassembleDebug/testDebugUnitTest/lintDebugが成功（50秒）。単体47件、失敗/エラー/skip 0。
lintは0 errors、9 warnings（既存ExifInterface 3 / UseKtx 6）、状態boxingのhint 2。チェック無効化なし。
初回lintはExperimentalDetector内部のFIR例外だったが、ソースを固定した再実行と最終修正後の実行で成功した。
型付き受付/実測、旧epoch/frameの無視、入力拒否とUNKNOWN、draft、露出AUTO保持/全resetを自動検証した。
Pixel9a上のセンサー反映、画面、画質、録画は今回未確認。US2/3はこの検証結果に含まない。

## 撮影UI US2: 撮影・設定・診断（2026-11-02）

`feat/004-capture-screen`候補でassembleDebug/testDebugUnitTest/lintDebug成功（最終候補で再確認）。単体52件、失敗/エラー/skip 0。
lintは0 errors、既存warning 9 / boxing hint 2。FIT・狭い画面の設定領域・Back優先順位を追加検証。
独立レビューでCamera権限の復帰時再確認、露出AUTOの復旧ガード、マイク拒否説明・端末設定で許可した後の拒否表示解除を修正。
PreviewViewをパネル切替で維持する構造を静的確認。実機での配置・タッチ・文字の視認性・センサー反映は未確認。
レイアウト単体テストは幾何と状態遷移を保証し、実描画や機種ごとのinsetsを保証するものではない。

canonicalレビューによるUI追加確認: Android9の共有保存非対応時はシャッター無効＋理由表示。Recorder準備/開始失敗時はdraftを保持し、実Start通知後だけ破棄する回帰を追加した。
通常時のCINE状態バー・選択操作帯のscrim重ね合わせは採用仕様であり、設定シートと画像を重ねない規則とは別である。
通常FIT_STARTは十分な高さのPixel9a基準で採用位置へ配置するが、狭いウィンドウで画像が横幅を満たさない場合の水平中央整列は未確認・既知の表示制約とする。

## 撮影UI US3: 配置固定と要素/出力回転（2026-11-02）

`feat/005-capture-orientation`の候補でassembleDebug/testDebugUnitTest/lintDebug成功（統合候補56秒）。単体57件、失敗/エラー/skip 0。
lint 0 errors、11 warnings（既存9＋採用portrait固定に対するLockedOrientationActivity/DiscouragedApi）、boxing hint 2。
向き固定の警告はユーザー採用仕様に対応し、検査は無効化しない。大画面/OEMの回転制御、実機上の継続は保証しない。
4方向の符号/境界hysteresis/最短アニメーション/全撮影・録画gateの出力固定を自動検証。
CameraX 1.6.2公開targetRotation APIのみ使用し、Previewと測光座標は回転しない。実機のJPEG/DNG/MP4向き、回転中録音、タッチ配置、可読性は未確認。

## 即時露出・有限応答待ち（Issue #37）

統合候補のassembleDebug/testDebugUnitTest/lintDebug成功（53秒）。単体66件、failure/error/skip 0。lint 0 errors / 14 warnings / 2 hints（既存11＋Handler利用のUseKtx提案3）。
標準段階/範囲/reciprocal、最新1件queue、操作期限/古い応答/長→短budget/背景破棄を自動・静的検証した。
端末の極端値で停止する正確な条件は未再現。新操作の実応答/改善とセンサー実測は実機未確認。

## CINE画角一致・設定overlay（Issue #38）

`fix/007-cine-framing`候補でassembleDebug/testDebugUnitTest/lintDebug成功（最終sourceで55秒）。単体70件、failure/error/skip 0。lint 0 errors / 14 warnings / 2 hints（既存件数と同じ）。チェック無効化なし。git diff --check成功。
CameraX 1.6.2公開SessionConfig.setViewPort/Recorder.setAspectRatio/Preview ResolutionSelectorをコンパイル・lint検証し、CINE query/対応判定/bindの同じViewPort共有を静的確認。STILL bindには適用しない。
設定/診断を開閉してもpreview高さが一定、modeだけでtop insetが決まるpolicyと、touchのclick通知が中央AF/AEを追加せずaccessibility clickが中央動作へ進むpolicyを自動検証。既存の全録画stage出力回転固定/Back/recording回帰も成功。
独立レビューで診断背面の撮影rail操作が残るP2を修正。診断中は全rail操作とcallbackを拒否し、非IDLEでは診断を閉じて停止操作を覆わないpolicyを全stageで追加検証した。draft破棄は実Start通知後のみ。
設定/診断の背景touch shieldはcontrols/scrollの背後に配置し、Previewのcallback guardとaccessibility非表示を併用。通常/設定ともFIT_CENTERへ固定し、過去のFIT_START水平整列制約と設定縮小はこの候補で置換する。
Spec Kit 005のspec/plan/research/data-model/contracts/quickstart/tasks、setup-plan/setup-tasks/check-prerequisitesとread-only整合分析を実施。FR6件/SC2件はtasksに対応、未解決の仕様矛盾なし。実機ACはtasks T012/T013として未完了。
実機撮影/録音/インストールなし。Preview/MP4四辺の一致、HDR/quality/fps全候補、4方向の画角・出力、設定scroll/touch/Back/TalkBack、STILL原本の端末上の保持は未検証。bound geometryと実MP4 metadataを区別し、今回の機械検証を実機合格としない。

## 長秒からの復旧（Issue #37 follow-up）

assembleDebug/testDebugUnitTest/lintDebug成功（53秒）。単体73件、failure/error/skip 0。lint errors 0 / warnings 14 / hints 2、既存件数から増加なし。
15s要求のtimeout後に旧受付pairへ戻す再送、旧epoch/UNKNOWN/受付時刻なしの重複判定、queued重複抑止、camera別の長秒期限hintとAUTO clear/短露光受付後の更新を自動検証した。
期限hintはメモリ内の保守的な待ち予算のみで、現在センサー状態や実測の証拠ではない。既知の長秒をstop/rebindで失わず、別cameraへ混用しない。
添付の正規転送は再準備後1回再試行しても失敗し、実画像/動画/ログは未確認。実機の15s非反映の直接原因と修正効果は未検証。repeating request自体の長秒によるpreview低fpsや撮影/preview露出分離はこの修正で解決したと扱わない。実機T009は未完了。

## 固定pair二段階露出simulation（Issue #42）

CameraX1.6.2公開APIでassembleDebug/testDebugUnitTest/lintDebug成功（最終候補70秒）。単体100件、failure/error/skip 0。追加27件は二段階計算17件、専用receipt/intent/epoch/保存後復帰/退出/背景化/AE・gain欠落の状態10件。実カメラの代用ではない。

lint errors 0 / warnings 15 / hints 2。warningはExifInterface 7、UseKtx 6、portrait固定のLockedOrientationActivity/DiscouragedApi各1。hintsは既存state boxing2。チェック無効化やbaseline追加なし。Framework Exifは既存と同じ標準読取で、ISO/露光metadataも実画像の輝度一致やfull RAW decodeの証拠にはしない。

STILLの明示opt-in固定pair、Builder単発設定とruntime preview補償、対応時だけ共通postRAW boost100をcompile確認。通常preferences/receiptと専用preview receiptを分離し、STILL intentはpreview sampleへ混入させない。保存と新PREVIEW復帰を分離、旧epoch/不完全AE・gain/退出/背景をpure stateと静的確認で検証。

検証範囲はspecs/006-exposure-simulationへ追跡。Spec Kitのspec/plan/tasks/read-only整合分析を行い、FR9件/SC3件に対応。実機T009は未完了。初回bind長秒混入、動的pair変更時の再bind、輝度/階調/noise/clippingの近似、実sensor周期/描画fps、単発設定・保存metadata、RAW/動画回帰は未実機。

コンパイル成功/同じISO×時間/READY表示を、露出分離や画質の実機成立としない。機能は通常OFF、pair変更は検証終了後、終了は保存済み通常設定へ再bind。第3描画mode/露出計/GPU/NDK/依存/新権限/署名変更なし。
# 通常STILL二段階preview・ISO/SS操作部（Issue #44 / #45）

- 検証source head: `74982af58021f1a9809d05dde2b38f21e9f3d8b6`。CameraX 1.6.2の公開APIを維持。
- `assembleDebug / testDebugUnitTest / lintDebug` 成功（69秒、53 tasks、18 executed / 35 up-to-date）。22 suites / 143 tests、failure・error・skip 0。
- lintはerror 0 / warning 15 / hint 2。ExifInterface 7、UseKtx 6、縦固定に関する2件、state boxing hints 2。抑制・baseline・チェック無効化なし。
- 新しい検証はpreview/capture/restore所有権、旧epoch/revision/receipt、準備失敗・timeout・保存と復帰、32ms最新要求置換、AUTO一時設定のkey/epoch/one-shot、背景復帰の未受付最新target保持/同scope再試行/要求identity/新ACK解除、RAW+JPEG callback順序/重複/失敗/IO所有権、狭幅/密度/横持ちText測定。
- 独立レビューのAUTO一時設定持越しと横持ちSS省略、およびcanonicalレビューの背景化前未受付target巻戻りを修正した。未受付targetは受付済みpreferencesと別のmemory-only要求で保持し、新sessionのACK後だけ保存・解除する。AUTO/reset受理時は失敗した旧MANUAL入力も無条件破棄し、UNKNOWNの明示AUTOは背景復帰へ保持する。3巡目補正前にqueue/live/selected/prefs/resumeのauthorityと破棄境界を読み取り分析し、この有限familyのみ補正した。core修正とUIそれぞれ独立レビューの明示OKを得た。最終headのcanonical/CIはPRへ記録する。
- 初期Preview seedは補償pairのみ、ImageCaptureに固定露出overrideなし。unbind前の保持Interop clear、AUTO例外再bind、背景停止とforeground復帰の呼出境界を静的に照合した。CameraXとの実際の相互作用をJVM test成功だけで保証しない。
- 通常経路のPixel 9a実機、全formatの撮影metadata、初回/復帰、明るさ/階調/描画fpsは未確認。runtime撮影pairのACK自体がrepeating結果待ちなので、長秒では撮影開始前・実撮影・復帰にセンサー由来の待ちが生じ得る。
- HTML参照は構造/CSSのみ確認。ブラウザがfileプロトコル表示を拒否したため、参照実描画との比較は未実施。縦横の測定モデルの機械検証と実機の見た目一致は別とする。ISO/SS以外の監査差分はPO判断待ちで実装していない。

## 撮影UIの状態・MF/WB・設定診断（Issue #47、最新PO反映）

- 検証source: `8d89db8ec3dfdd4bb4187981a1f46c6a9335d3be`。assembleDebug/testDebugUnitTest/lintDebug成功76秒、53 tasks（16 executed / 37 up-to-date）。27 suites / 189 tests、failure/error/skip 0。
- lint error 0 / warnings 15 / hints 2。ExifInterface7、UseKtx6、縦固定2件、state boxing hints2。抑制・baseline・チェック無効化なし。変更ファイルの公開前scanとdiffcheck成功。
- ISO/SS主値は現epochのPREVIEW/VIDEO_RECORD実測を最寄り標準候補へ≈表示する仕様を採用。各軸の値/候補/intent/2000ms鮮度を検査し、未知・旧値は—。500ms tickはlifecycle STARTEDだけで動作し、表示値を選択/設定へ書き戻す経路はない。撮影targetは目盛りで選択する。補償previewの実測を撮影ISO/SSと混同しない。
- 論理capture pairとWB/MFのfield差分だけにpendingを帰属。modeは状態バー1回、主値はキー、targetは目盛り、送信・完了/失敗は共通notice、進行は状態バー、要求/受付/実測の詳細は診断へ集約する。無関係な項目を送信中/受付確定としない。
- detent距離蓄積、端点/同値/無効、tap/矢印/accessibility共通action、実変更時だけCLOCK_TICK(flags0)をunitとcallsiteで検証。新VIBRATE権限や独自振動engineなし。端末の実触感・haptic無効の実機設定は未確認。
- 最新POは旧HTMLより優先。STILL大白/CINE小白、STARTING白、実RECORDINGだけ赤（preview枠も含む）。下captionは非描画。保持AudioStats、実準備abortとsingle後失敗のREADY復帰、mode/鮮度/新旧epoch・表示source分離を回帰検証した。
- camera callbackへCaptureSample intentを記録する観測情報だけを追加し、露出/録画/保存pipelineは変更しない。SS上限の短縮やRAW現像は本変更に含めない。
- HTML実描画はfile URLの明示security policy拒否（HTTP/HTTPSのみ許可、認可elicitationなし、迂回禁止）のため未確認。構造/CSS確認と測定モデルを実描画合格へ読み替えない。端末font scale/insets/横持ち可読性/タッチ/触感/画質・実センサー反映は未実機。
- Spec Kit 009はFR11/SC4に対応。最終headの独立/canonical/CI/thread証拠はPRへ記録し、実機AC・追加UIのmerge/配布は別承認待ち。
- canonical指摘のMF受付完了notice欠落と設定内MIC重複を補正。送信前後の同epoch・新sequence発行を確認し、backend guard未開始なら旧ACCEPTEDを新成功として採用せずdraftを保持する。同期/非同期完了を同じtyped helperへ接続し、API受付noticeは一度だけ。新旧receipt/未開始/重複の回帰を追加した。
- 最後の表示専用差分として、設定内の出力requested/applied・bind詳細を診断へ移動した。Kotlin再compile成功（11秒、全7tasks up-to-date）。上記189testsのfull検証とは分け、最終headの自然CIでbuild/unit/lintを再確認する。

## 通常MANUALの暫定1秒上限（Issue #37 / #44 follow-up）

- 統合検証source `7c1eaf19947d61ead9d6321489f125dc9be8cb48`（UI/PC PoCを含むmainへ同期済み）。assembleDebug/testDebugUnitTest/lintDebug成功78秒、53 tasks（18 executed / 35 up-to-date）。28 suites / 197 tests、failure/error/skip 0。
- lint 0 errors / 15 warnings / 2 hints、抑制・baseline・チェック無効化なし。端末実能力は不変、通常MANUAL候補/新入力/旧保存・復帰だけ暫定1秒と端末制約の交差を適用。最短SS・CINE fps制約を維持。
- 8 focused testsで1秒境界、1ns超過、旧長秒のISO保持移行、現在device upperが旧設定より短い場合、合法標準候補なし、冪等を検証。移行設定は新API受付後だけ保存し、旧実測を移行要求の証拠へ流用しない。AUTOのAE実露光を1秒以内と保証しない。
- 最新mainとの競合は移行noticeを既存共通noticeへ接続し、policy詳細をCapturePanels診断へ置く形で解消。独立レビューの最終head明示OK。最終remote gateはIssue/PRコメントへ残す。
- 実機移行/全format撮影/復帰の応答は未確認。1秒制限を数秒撮影の停止原因解消と扱わない。既存撮影経路は撮影pair受付→単発撮影/file callback→保存IO→preview設定受付/新frameの直列区間があり、主な遅延区間は実測ログで別途切り分ける。
- 独立追加レビューで、camera切替後に旧移行受付の診断文字列を現sessionと混同するP2を補正。移行開始時のcamera ID/mode/epochを捕捉し、要求notice・受付履歴の両方へ履歴scopeを明示する（新状態や撮影動作変更なし）。
- 上記履歴scope表示の補正後、Kotlin compile成功21秒（7tasks、1executed/6up-to-date）。最終headの自然CIで全unit/build/lintを再確認する。

## v15実機フィードバックのUI修正（Issue #47 / #37）

- assembleDebug/testDebugUnitTest/lintDebug成功81秒、53 tasks全実行。29 suites / 213 tests、failure/error/skip 0。lint 0 errors / 15 warnings / 2 hints、抑制・baseline・チェック無効化なし。
- MANUAL主値は現epochの最新logical撮影target、AUTOは軸別に保持した有効preview実測を「約」表示。欠損/単発撮影/UNKNOWN観測で正常な別軸を空白化せず、元timestampの2秒期限とepoch resetを維持。観測は通常500msで、preview以外の観測が更新を延期しない。
- 固定RAW/単発target参照・epoch/revision、AUTO欠損/失効、最新要求、detent距離・同値/端点/無効、EV絶対index/即時単一要求、MF exact draft/apply、WB対応preset、shutter58/48dpと実録画赤、ユーザー自動回転設定のON/OFF/左右/180度/途中toggleと録画出力freezeをpure/state・callsiteで検証。実ファイルISOや描画fpsの実測には読み替えない。
- Android自動回転設定は標準Settings/SystemとContentObserverで読取りのみ。UI OFFはportrait、ONはphysical。物理保存方向と録画開始方向固定は分離し、observerはstop/destroy解除。新権限/設定書込なし。
- sourceの独立レビューは追加P1/P2なし。公開scanで秘密/個人絶対path/媒体混入なし、RAW PC tools変更なし。最終headのcanonical/必須CI/thread証拠はPRへ記録する。
- 実機のちらつき/感度表示/全format metadata、触感/縦横可読性、自動回転設定の端末挙動は未確認。v15のユーザー確認と今回変更の受入を分け、merge/配布は承認待ち。

## v16後続: 連続操作intentとAUTO可読性（Issue #47 / #37）

- 最終sourceのassembleDebug/testDebugUnitTest/lintDebug成功81秒、53 tasks（15 executed / 38 up-to-date）。30 suites / 229 tests、failure/error/skip 0。lint error0/warning15/hint2、抑制・baseline・チェック無効化なし。
- EV/WB/MFとISO/SSをlogical ControlSnapshotのflight1+pending1へ統合し、32ms集約・ACK後latest送信。UIはpendingでも最新intentを受け付け、古い受付で戻さない。EV連続段階、WB連続/default、MF往復/AF/∞、古いACK、失敗・epoch・背景/再接続をpure production stateとcallsiteで検証した。旧ISO専用queueの実装依存7testsを新aggregate回帰へ置換し、deadline/history4件を保持。新queue系15testsはmailbox/seed/legacyAUTO復帰を含む。
- MFの適用/取消を廃止し、操作中も最新要求を送る。capture/録画/未知状態の安全guardは維持。AUTO/reset失敗後のsame-scope rebindで論理AUTOを失わないようAPI前logical seedを保持し、明示fullresetのrequestedPrefs解除を両経路で統一。通常項目別保存とunsupported fallback保持は変更しない。
- AUTO表示用の正常軸をcallback throttle前にbounded mailboxへ集約。元timestampの2秒失効/epochを維持し、source/frame/時刻/age/freshnessを既存診断とreportへ記録する。これは取り逃し得るsource経路の補正であり、端末の支配的なちらつき原因の確定ではない。
- 近似説明と数値を分離。TextMeasurerでfontScale込みの幅と回転後footprintを測り、キー列scrollと目盛り5/3/1の適応を行う。主要数値ellipsisを撤去し、preview寸法と停止railを維持。fontScale1.0/1.3/2.0の測定policyを機械検証し、実端末glyph/raster可読性は未確認。
- source最終独立reviewはcore/UIとも追加P1/P2なし、公開scan/diffcheck成功、RAWtools/原画像/依存/他repo変更なし。Spec013はFR7/SC4に対応。最終headのcanonical/必須CI/thread証拠はPRへ記録し、実機とmerge/配布は未承認・未確認のまま。

## 設定シートのデザイン反映（Issue #172）

- 専用worktreeでlintDebug / testDebugUnitTest / assembleDebugとdiff checkが成功。116 suites / 842 tests、failure/error 0、skip 4。lintは0 errors / 62 warnings / 2 hints。既存の警告を抑制していない。
- 専用Android 37 AVD、412×924dp相当で設定本文・固定フッター、6項目3列のリセット確認、fontScale 1.3の表示とスクロールを確認。リセット実行、実機の全能力・録画中・再接続・横持ち操作は未確認。
- preview配置と背後入力shield、各設定callback/guardは保持。RAW現像、原本、保存ライフサイクルには変更なし。AVD表示確認を実機の画質・24MP保存・性能検証へ読み替えない。

## 診断画面のデザイン反映（Issue #173）

- lintDebug / testDebugUnitTest / assembleDebugが成功。116 suites / 842 tests、failure/error 0、skip 4。熱状態サマリーを短いOS状態名に限定する修正後も全検証成功。全文は出力タブに保持。
- 専用Android 37 AVDで制御表、出力タブ切替、固定Preview/レポート操作の表示を確認。レポート保存の実行、全カメラ能力、実機の横持ち/コピー操作は未確認。
- API要求・受付履歴・直近実測、ISO/SS source/観測期限、能力/ログをタブに移し、SelectionContainerと元データ・操作guardを保持。RAW/保存処理には変更なし。
