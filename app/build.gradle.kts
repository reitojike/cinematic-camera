plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "dev.cinematic.p0"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.cinematic.p0"
        minSdk = 28
        targetSdk = 37
        versionCode = providers.environmentVariable("CINEMATIC_VERSION_CODE").orNull?.toInt() ?: 6
        versionName = providers.environmentVariable("CINEMATIC_VERSION_NAME").orNull ?: "0.0.6-p05"
    }
    buildFeatures { compose = true; buildConfig = true }
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
