package dev.cinematic.p0.jpegrender.original

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.rawcapture.AndroidAutoDevelopQueue
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

private var isolatedSaveApp: Context? = null

/** One fresh process/class and namespace, initialized before Activity/service admission. */
@Synchronized internal fun isolatedGpuSaveSafetyApp(): Context {
    requireGpuSaveSafetyFixture()
    val selector = InstrumentationRegistry.getArguments().getString("class").orEmpty()
    check(Regex("[A-Za-z0-9_.]+(?:#[A-Za-z0-9_]+)?").matches(selector)) {
        "Only one class or one class#method selector is permitted"
    }
    val selected = selector.substringBefore('#')
    check(selected in setOf(
        "dev.cinematic.p0.jpegrender.original.GpuPreviewPublicationSafetyAndroidTest",
        "dev.cinematic.p0.jpegrender.original.GpuFullSizeSaveSafetyAndroidTest",
        "dev.cinematic.p0.photoroll.GpuActiveSaveOwnershipSafetyAndroidTest",
        "dev.cinematic.p0.jpegrender.original.GpuConcurrentInputSafetyAndroidTest",
        "dev.cinematic.p0.photoroll.GpuSaveLifecycleSafetyAndroidTest",
        "dev.cinematic.p0.photoroll.GpuFullSizeSaveLifecycleSafetyAndroidTest",
    )) { "Run one allowed safety class in a fresh instrumentation process" }
    return isolatedSaveApp ?: run {
        val target = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val namespace = "gpu-save-safety-${UUID.randomUUID()}"
        val root = File(target.cacheDir.canonicalFile, namespace)
        check(root.mkdir() && root.canonicalFile.parentFile == target.cacheDir.canonicalFile)
        val cache = File(root, "cache").also { check(it.mkdir()) }
        val state = File(root, "state").also { check(it.mkdir()) }
        val app = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = cache
            override fun getNoBackupFilesDir(): File = state
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                target.getSharedPreferences("$namespace-$name", mode)
        }
        // Production recovery sees only the new empty state directory, never existing app records.
        AndroidAutoDevelopQueue.startupCompletion(app).get(60, TimeUnit.SECONDS)
        app.also { isolatedSaveApp = it }
    }
}