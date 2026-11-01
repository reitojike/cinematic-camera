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
