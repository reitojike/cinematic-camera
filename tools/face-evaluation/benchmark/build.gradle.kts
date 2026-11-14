import java.security.MessageDigest
import java.util.zip.ZipFile

plugins { id("com.android.application") }

android {
    namespace = "org.cinematic.evaluation"
    compileSdk = 37
    defaultConfig {
        applicationId = "org.cinematic.evaluation"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "evaluation-only"
        testInstrumentationRunner = "org.cinematic.evaluation.SyntheticRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    flavorDimensions += "detector"
    productFlavors {
        create("baseline") { dimension = "detector"; applicationIdSuffix = ".baseline" }
        create("mlkit") { dimension = "detector"; applicationIdSuffix = ".mlkit" }
        create("yunet") { dimension = "detector"; applicationIdSuffix = ".yunet" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Model is supplied outside the repository. No network/download task is run by this project.
    sourceSets.getByName("yunet").assets.srcDir(layout.buildDirectory.dir("model-assets").get().asFile)
}
dependencies {
    "mlkitImplementation"("com.google.mlkit:face-detection:16.1.7")
    "yunetImplementation"("org.opencv:opencv:4.13.0")
}
val stageModel = tasks.register("stageModel") {
    val model = providers.environmentVariable("FACE_EVAL_MODEL")
    outputs.upToDateWhen { false }
    doLast {
        require(model.isPresent) { "Set FACE_EVAL_MODEL to the external fixed 2023mar model" }
        val source = file(model.get())
        val hash = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
            .joinToString("") { "%02x".format(it) }
        require(hash == "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4") { "Model hash mismatch" }
        val destination = layout.buildDirectory.file("model-assets/face_detection_yunet_2023mar.onnx").get().asFile
        destination.parentFile.mkdirs()
        source.copyTo(destination, overwrite = true)
    }
}
tasks.configureEach {
    if (name.startsWith("mergeYunet") && name.endsWith("Assets")) dependsOn(stageModel)
}
tasks.register("verifyEvaluation") {
    for (flavor in listOf("Baseline", "Mlkit", "Yunet")) {
        dependsOn("assemble${flavor}Debug", "assemble${flavor}DebugAndroidTest", "lint${flavor}Debug")
    }
}
// Preserve license files at AAR roots: Android packaging does not copy these into assets itself.
for (flavor in listOf("baseline", "mlkit", "yunet")) {
    val cap = flavor.replaceFirstChar { it.uppercaseChar() }
    val noticesDir = layout.buildDirectory.dir("notices/$flavor").get().asFile
    android.sourceSets.getByName(flavor).assets.srcDir(noticesDir)
    val notices = tasks.register("stage${cap}Notices") {
        outputs.upToDateWhen { false }
        doLast {
            // Delete only this task's generated directory; never an external input directory.
            require(noticesDir.canonicalFile.toPath().startsWith(layout.buildDirectory.get().asFile.canonicalFile.toPath()))
            noticesDir.deleteRecursively()
            val inventory = mutableListOf("coordinate\tsha256\tnotice_entries")
            configurations.getByName("${flavor}DebugRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
                .sortedBy { it.moduleVersion.id.toString() }.forEach { artifact ->
                    val coordinate = artifact.moduleVersion.id.toString()
                    val hash = MessageDigest.getInstance("SHA-256").digest(artifact.file.readBytes())
                        .joinToString("") { "%02x".format(it) }
                    var count = 0
                    ZipFile(artifact.file).use { archive ->
                        archive.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
                            val leaf = entry.name.substringAfterLast('/').lowercase()
                            if (leaf.startsWith("license") || leaf.startsWith("notice") || leaf.startsWith("third_party_licenses")) {
                                val target = noticesDir.resolve("licenses/${coordinate.replace(':', '_')}/$count-${entry.name.substringAfterLast('/')}")
                                target.parentFile.mkdirs()
                                archive.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                                count++
                            }
                        }
                    }
                    inventory += "$coordinate\t$hash\t$count"
                }
            noticesDir.mkdirs()
            noticesDir.resolve("dependency-inventory.tsv").writeText(inventory.joinToString("\n") + "\n")
        }
    }
    tasks.configureEach {
        if (name.startsWith("merge${cap}") && name.endsWith("Assets")) dependsOn(notices)
    }
}
