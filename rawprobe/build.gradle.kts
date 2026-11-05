plugins { id("com.android.application") }

android {
    namespace = "dev.cinematic.rawprobe"
    compileSdk = 37
    ndkVersion = "29.0.14206865"
    externalNativeBuild {
        cmake { path = file("../app/src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
    defaultConfig {
        applicationId = "dev.cinematic.rawprobe"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1-probe"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { arguments += "-DCINEMATIC_ENABLE_RAW_PROBE=ON" } }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = true }
    sourceSets {
        getByName("main").assets.directories.add("../app/src/main/assets")
        getByName("androidTest").kotlin.directories.add("../app/src/androidTest/java/dev/cinematic/p0/testing")
    }
}

// A separate, debug-only diagnostic package: never replaces the installed camera app.
androidComponents.beforeVariants(androidComponents.selector().withBuildType("release")) { it.enable = false }

dependencies {
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("junit:junit:4.13.2")
    // Match the versions already selected by :app's instrumentation classpath.
    constraints {
        androidTestImplementation("androidx.annotation:annotation-jvm:1.9.1")
        androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    }
}
