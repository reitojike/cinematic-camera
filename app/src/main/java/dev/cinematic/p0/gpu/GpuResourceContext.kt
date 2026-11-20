package dev.cinematic.p0.gpu

/** Ownership and current-context checks used by context-owned GPU programs and fences. */
internal interface GpuResourceContext {
    fun requireOwnerThread()
    fun requireCurrent()
    fun checkGl(operation: String)

    /** Run explicit deletion only while a live context still owns its GL names. */
    fun releaseResource(block: () -> Unit)
}
