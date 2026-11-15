plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Host-only shader regression tests. Nothing is added to an APK or a normal test run.
val gpuMesa = providers.gradleProperty("cinematicGpuMesa").orNull == "true"

// Draft opt-in: default debug builds require no signing inputs and remain unchanged.
val cinematicSigningMode = providers.environmentVariable("CINEMATIC_SIGNING_MODE").orElse("ephemeral_debug").get()
require(cinematicSigningMode in setOf("ephemeral_debug", "stable_internal")) {
    "CINEMATIC_SIGNING_MODE must be ephemeral_debug or stable_internal"
}
val stableInternalSigning = cinematicSigningMode == "stable_internal"
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
    // Legacy CPU trial safety fixtures stay executable in tests, never in an app APK.
    sourceSets.getByName("test").kotlin.srcDir("src/testSupport/java")
    sourceSets.getByName("androidTest").kotlin.srcDir("src/testSupport/java")
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
        getByName("release") {
            isDebuggable = false
            if (stableInternalSigning) signingConfig = signingConfigs.getByName("cinematicInternal")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        if (gpuMesa) {
            unitTests.all {
                if (it.name != "testDebugUnitTest") return@all
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
    }
    if (gpuMesa) {
        sourceSets.getByName("testDebug").kotlin.srcDir("src/testGpuMesa/java")
    }
}
if (gpuMesa) {
    // AGP's AndroidSourceDirectorySet has no include/exclude API. Add only the
    // three shared helpers to Kotlin's source inputs, in place and without copies.
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        if (name == "compileDebugUnitTestKotlin") {
            source(fileTree("src/androidTest/java/dev/cinematic/p0/gpu/reference") {
                include("GpuReferenceImages.kt", "GpuTolerances.kt", "ImageDiff.kt")
            })
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
        val lwjgl = "3.4.3"
        add("testDebugImplementation", "org.lwjgl:lwjgl:$lwjgl")
        add("testDebugImplementation", "org.lwjgl:lwjgl-egl:$lwjgl")
        add("testDebugImplementation", "org.lwjgl:lwjgl-opengles:$lwjgl")
        add("testDebugRuntimeOnly", "org.lwjgl:lwjgl:$lwjgl:natives-linux")
        add("testDebugRuntimeOnly", "org.lwjgl:lwjgl-opengles:$lwjgl:natives-linux")
    }
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.1")
}
