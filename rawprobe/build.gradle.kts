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
    flavorDimensions += "heapPolicy"
    productFlavors {
        create("normal") {
            dimension = "heapPolicy"
            manifestPlaceholders["probeLargeHeap"] = "false"
        }
        create("largeHeap") {
            dimension = "heapPolicy"
            applicationIdSuffix = ".largeheap"
            manifestPlaceholders["probeLargeHeap"] = "true"
        }
    }
    lint { abortOnError = true }
    sourceSets {
        getByName("main").assets.directories.add("../app/src/main/assets")
        getByName("androidTest").kotlin.directories.add("../app/src/androidTest/java/dev/cinematic/p0/testing")
        // Compile the current production kernels for measurement; no forked algorithm copies.
        getByName("androidTest").kotlin.directories.add("../app/src/main/java/dev/cinematic/p0/raw")
    }
}

// A separate, debug-only diagnostic package: never replaces the installed camera app.
androidComponents.beforeVariants(androidComponents.selector().withBuildType("release")) { it.enable = false }

dependencies {
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("junit:junit:4.13.2")
    // Explicit minimum versions for the standalone probe's instrumentation classpath.
    // These test-only constraints do not update :app's dependencies.
    constraints {
        androidTestImplementation("androidx.annotation:annotation-jvm:1.11.0")
        androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    }
}
