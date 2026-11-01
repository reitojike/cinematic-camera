plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

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
    defaultConfig {
        applicationId = "dev.cinematic.p0"
        minSdk = 28
        targetSdk = 37
        versionCode = providers.environmentVariable("CINEMATIC_VERSION_CODE").orNull?.toInt() ?: 9
        versionName = providers.environmentVariable("CINEMATIC_VERSION_NAME").orNull ?: "0.0.9-p05"
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
        getByName("release") {
            isDebuggable = false
            if (stableInternalSigning) signingConfig = signingConfigs.getByName("cinematicInternal")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = true }
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
}
