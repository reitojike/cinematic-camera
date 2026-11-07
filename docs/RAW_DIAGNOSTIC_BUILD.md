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
入力copyは終了時に削除、成功JPEGは各試行後削除。失敗scratchと数値reportは私有領域に残る場合がある。
選択元のbytesを最後に再照合する。試行のsourceUnchangedは私有入力、selectedOriginalUnchangedは選択元の検査を指す。

APK導入、Files選択、比較条件/測定modeの選択、開始、取消、結果共有は本人の操作として別途案内する。
ビルド成功は写真利用・インストール・端末操作の許可を意味しない。署名不一致をapp削除で回避しない。

## 比較と反復

- R7 Foundation: 旧baseline 1 workerと2 worker。empty ROIのみ並列、外部ROI callbackはserial。
  通常原寸手動保存のcallerは2を明示する。Original24Attempt/OriginalStageClear24のAPI既定は1を維持し、
  この診断も製品callerを使わず各armの1/2を明示する。tone gain onceの過去変更とは別の比較。
- Glow: 1対2、または1対4を選択。light/diffusionの2つの行合成だけ並列化し、間のglobal blur境界を保持。
  worker専有行buffer、既存ordered executorとowner管理を使う。通常既定1を変えない。
- 一つのsessionはwarmup AB、その後ABBAAB（逆順はBA/BAABBA）。各arm初回除外後ちょうど3成功、計8回。
  失敗/取消/不一致があれば停止し、不足分を成功扱いせず、少数試行からP95や確定的改善率を出さない。
- 同じsession内の入力・設定は固定。strength=.5、warmth/追加EV=0、Orton4/Character2。
  比較対象以外のFoundation/Glowは1。最初の成功JPEGを基準に全試行のfingerprint一致をboolだけ残す。
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

メモリmodeは別session。before、各stage完了、afterの不定間隔境界でmanaged使用量、native allocated bytes、
PSS KiBを観測し、native/PSSはsampled maxも出す。周期samplerではなく、工程内部の瞬間peakを捉えない。
Debug.getMemoryInfo等の負荷を含むためメモリmodeから時間改善を主張しない。各項目の最大値を足して同時peakとしない。

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

## 合成検証の証拠

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
