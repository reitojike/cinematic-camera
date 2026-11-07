plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val original24Probe = providers.gradleProperty("cinematicOriginal24Probe").orNull == "true"
val rawDiagnostic = providers.gradleProperty("cinematicRawDiagnostic").orNull == "true"
require(!(original24Probe && rawDiagnostic)) { "Choose one diagnostic build type" }
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
require(!rawDiagnostic || !stableInternalSigning) { "RAW diagnostics use ephemeral_debug signing only" }
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
    if (original24Probe) {
        testBuildType = "original24Probe"
        sourceSets.getByName("androidTest").manifest.srcFile("src/original24ProbeTest/AndroidManifest.xml")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
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
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
