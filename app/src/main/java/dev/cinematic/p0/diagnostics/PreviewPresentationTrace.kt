package dev.cinematic.p0.diagnostics

import android.graphics.Bitmap
import android.os.Trace
import android.util.Log
import java.util.WeakHashMap

/** 明示DEBUG＋実際のtrace収集中だけ。既定オフ、時刻・IDだけを記録する。 */
internal object PreviewPresentationTrace {
    private val ledger = PreviewPresentationLedger()
    private val activeInput = ThreadLocal<Long?>()
    private val images = WeakHashMap<Bitmap, PreviewPresentationLedger.Request>()
    private fun enabled(): Boolean = optional { Log.isLoggable("PreviewPresentation", Log.DEBUG) && Trace.isEnabled() } == true
    private fun <T> optional(block: () -> T): T? = try { block() } catch (_: Throwable) { null }
    private fun mark(name: String) { optional { Trace.beginSection(name); Trace.endSection() } }

    fun <T> input(eventUptimeMs: Long, released: Boolean, block: () -> T): T {
        if (!enabled()) return block()
        val previous = optional { activeInput.get() }
        val id = optional { ledger.nextInput() }
        optional { activeInput.set(id) }
        if (id != null) mark("cc.preview.input:$id:$eventUptimeMs:${if (released) "UP" else "MOVE"}")
        try { return block() } finally { optional { activeInput.set(previous) } }
    }
    fun bind(key: Any) {
        if (enabled()) optional { activeInput.get()?.let { ledger.bind(key, it) } }
    }
    fun start(key: Any, exact: Boolean): PreviewPresentationLedger.Request? {
        if (!enabled()) return null
        val request = optional { ledger.start(key, exact) } ?: return null
        mark("cc.preview.request:${request.id}:${request.inputId}:${if (exact) "EXACT" else "DRAFT"}")
        return request
    }
    fun adopted(bitmap: Bitmap, request: PreviewPresentationLedger.Request?) {
        if (request == null || !enabled()) return
        optional { synchronized(images) { images[bitmap] = request } }
        mark("cc.preview.adopt:${request.id}")
    }
    fun <T> draw(bitmap: Bitmap, block: () -> T): T {
        val request = if (enabled()) optional { synchronized(images) { images[bitmap] } } else null
        val traced = request != null && optional { Trace.beginSection("cc.preview.draw:${request.id}"); true } == true
        try { return block() } finally { if (traced) optional { Trace.endSection() } }
    }
}
