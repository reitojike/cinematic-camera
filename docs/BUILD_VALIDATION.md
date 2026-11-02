# P0 build evidence — 2026-11-01

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
