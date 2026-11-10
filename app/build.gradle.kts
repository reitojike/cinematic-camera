plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val original24Probe = providers.gradleProperty("cinematicOriginal24Probe").orNull == "true"
val rawDiagnostic = providers.gradleProperty("cinematicRawDiagnostic").orNull == "true"
val previewDiagnostic = providers.gradleProperty("cinematicPreviewDiagnostic").orNull == "true"
val gpuDiagnostic = providers.gradleProperty("cinematicGpuDiagnostic").orNull == "true"
// Host-only shader regression tests. Nothing is added to an APK or a normal test run.
val gpuMesa = providers.gradleProperty("cinematicGpuMesa").orNull == "true"
require(listOf(original24Probe, rawDiagnostic, gpuDiagnostic, previewDiagnostic).count { it } <= 1) { "Choose one diagnostic build type" }
// A variant-specific false positive must never suppress a normal or aggregate lint invocation.
val diagnosticLintTasks = setOf("lintRawDiagnostic", "testRawDiagnosticUnitTest", "assembleRawDiagnostic", "processReleaseMainManifest")
val requestedTaskNames = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }
val diagnosticLintInvocation = rawDiagnostic && "lintRawDiagnostic" in requestedTaskNames &&
    requestedTaskNames.all { it in diagnosticLintTasks }

// Draft opt-in: default debug builds require no signing inputs and remain unchanged.
val cinematicSigningMode = providers.environmentVariable("CINEMATIC_SIGNING_MODE").orElse("ephemeral_debug").get()
require(cinematicSigningMode in setOf("ephemeral_debug", "stable_internal")) {
    "CINEMATIC_SIGNING_MODE must be ephemeral_debug or stable_internal"
}
val stableInternalSigning = cinematicSigningMode == "stable_internal"
require(!(rawDiagnostic || gpuDiagnostic || previewDiagnostic) || !stableInternalSigning) { "RAW diagnostics use ephemeral_debug signing only" }
fun requiredSigningInput(name: String): String = providers.environmentVariable(name).orNull
    ?.takeIf { it.isNotBlank() } ?: throw GradleException("Stable internal signing requires environment input: $name")
if (stableInternalSigning) {
    require(!gradle.startParameter.isConfigurationCacheRequested) { "Stable signing requires --no-configuration-cache" }
}
android {
    namespace = "dev.cinematic.p0"
    compileSdk = 37
    ndkVersion = "29.0.14206865"
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
    defaultConfig {
        applicationId = "dev.cinematic.p0"
        buildConfigField("boolean", "EDITOR_PREVIEW_DIAGNOSTIC", "false")
        minSdk = 33
        targetSdk = 37
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = providers.environmentVariable("CINEMATIC_VERSION_CODE").orNull?.toInt() ?: 10
        versionName = providers.environmentVariable("CINEMATIC_VERSION_NAME").orNull ?: "0.0.10-p05"
    }
    buildFeatures { compose = true; buildConfig = true }
    signingConfigs {
        if (stableInternalSigning) {
            create("cinematicInternal") {
                storeFile = file(requiredSigningInput("CINEMATIC_INTERNAL_KEYSTORE_FILE")).also {
                    require(it.isFile) { "Stable internal signing keystore is missing" }
                }
                storeType = "PKCS12"
                storePassword = requiredSigningInput("CINEMATIC_INTERNAL_STORE_PASSWORD")
                keyAlias = requiredSigningInput("CINEMATIC_INTERNAL_KEY_ALIAS")
                keyPassword = requiredSigningInput("CINEMATIC_INTERNAL_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        if (original24Probe) {
            create("original24Probe") {
                initWith(getByName("debug"))
                applicationIdSuffix = ".original24probe"
                matchingFallbacks += "debug"
            }
        }
        getByName("release") {
            isDebuggable = false
            if (stableInternalSigning) signingConfig = signingConfigs.getByName("cinematicInternal")
        }
    }
    // Same optimization recipe as the earlier Pixel photo probe, not the same measurement harness.
    if (rawDiagnostic) {
        if (providers.gradleProperty("cinematicRawDiagnosticTests").orNull == "true") {
            testBuildType = "rawDiagnostic"
            sourceSets.getByName("androidTest").manifest.srcFile("src/rawDiagnosticTest/AndroidManifest.xml")
        }
        buildTypes.create("rawDiagnostic") {
            initWith(buildTypes.getByName("release"))
            applicationIdSuffix = ".rawdiagnostic"
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            externalNativeBuild { cmake { arguments += "-DCMAKE_BUILD_TYPE=RelWithDebInfo" } }
        }
    }
    // GPU 診断は明示指定だけで作る独立アプリ。通常 CI と製品の source set には含めない。
    if (gpuDiagnostic) {
        if (providers.gradleProperty("cinematicGpuDiagnosticTests").orNull == "true") {
            testBuildType = "gpuDiagnostic"
            sourceSets.getByName("androidTest").kotlin.srcDir("src/gpuDiagnosticTest/java")
            sourceSets.getByName("androidTest").manifest.srcFile("src/gpuDiagnosticTest/AndroidManifest.xml")
        }
        buildTypes.create("gpuDiagnostic") {
            initWith(buildTypes.getByName("release"))
            applicationIdSuffix = ".gpudiagnostic"
            buildConfigField("boolean", "EDITOR_PREVIEW_DIAGNOSTIC", "true")
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    // 操作・表示相関の専用host。既存の統合診断と通常CIには接続しない。
    if (previewDiagnostic) {
        buildTypes.create("previewDiagnostic") {
            initWith(buildTypes.getByName("release"))
            applicationIdSuffix = ".previewdiagnostic"
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
        if (providers.gradleProperty("cinematicPreviewDiagnosticTests").orNull == "true") {
            testBuildType = "previewDiagnostic"
            sourceSets.getByName("androidTest").manifest.srcFile("src/previewDiagnosticTest/AndroidManifest.xml")
        }
    }
    if (gpuDiagnostic) sourceSets.getByName("gpuDiagnostic").kotlin.srcDir("src/rawDiagnostic/java")
    if (original24Probe) {
        testBuildType = "original24Probe"
        sourceSets.getByName("androidTest").manifest.srcFile("src/original24ProbeTest/AndroidManifest.xml")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        if (gpuMesa) {
            unitTests.all {
                it.systemProperty("gpu.mesa.assetRoot", file("src/main/assets/gpu").absolutePath)
                it.systemProperty("gpu.mesa.testAssetRoot", file("src/androidTest/assets/gpu").absolutePath)
                it.maxHeapSize = "3g"
                it.maxParallelForks = 1
                it.testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
                it.reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/gpuMesa/${it.name}"))
                it.reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/gpuMesa/${it.name}"))
                // Assets live outside the normal JVM test inputs: shader-only edits must rerun the test.
                it.inputs.dir("src/main/assets/gpu")
                it.inputs.dir("src/androidTest/assets/gpu")
                // A different Mesa/driver must execute, never reuse a cached success.
                it.outputs.upToDateWhen { false }
                it.outputs.cacheIf { false }
            }
        }
        managedDevices {
            localDevices {
                create("pixel9aapi37") {
                    device = "Pixel 9a"
                    sdkVersion = 37
                    systemImageSource = "google"
                }
                // Automated Test Device: no SystemUI/IME/most Google apps, hardware rendering disabled.
                create("pixel9aapi37atd") {
                    device = "Pixel 9a"
                    sdkVersion = 37
                    systemImageSource = "google-atd"
                }
            }
        }
    }
    lint {
        abortOnError = true
        if (diagnosticLintInvocation) lintConfig = file("src/rawDiagnostic/lint.xml")
    }
    if (gpuMesa) {
        sourceSets.getByName("test").java.apply {
            srcDir("src/testGpuMesa/java")
            // Compile the current comparison helpers in place, not a Mesa-specific copy.
            srcDir("src/androidTest/java/dev/cinematic/p0/gpu/reference")
            exclude("**/GpuReferenceImagesTest.kt", "**/KotlinReferenceTest.kt")
        }
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    val cameraX = "1.6.2"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    implementation("androidx.camera:camera-video:$cameraX")
    testImplementation("junit:junit:4.13.2")
    if (gpuMesa) {
        val lwjgl = "3.3.6"
        testImplementation("org.lwjgl:lwjgl:$lwjgl")
        testImplementation("org.lwjgl:lwjgl-egl:$lwjgl")
        testImplementation("org.lwjgl:lwjgl-opengles:$lwjgl")
        testRuntimeOnly("org.lwjgl:lwjgl:$lwjgl:natives-linux")
        testRuntimeOnly("org.lwjgl:lwjgl-opengles:$lwjgl:natives-linux")
    }
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
