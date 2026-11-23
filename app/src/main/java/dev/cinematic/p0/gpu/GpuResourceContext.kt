package dev.cinematic.p0.gpu

/** Ownership and current-context checks used by context-owned GPU programs and fences. */
internal interface GpuResourceContext {
    val syncOperations: GpuSyncOperations
    fun requireOwnerThread()
    fun requireCurrent()
    fun checkGl(operation: String)

    /** Run explicit deletion only while a live context still owns its GL names. */
    fun releaseResource(block: () -> Unit)
}

/** Execution services borrowed from the same owner as textures and programs. */
internal interface GpuExecutionContext : GpuResourceContext {
    val passOperations: GpuPassOperations
    val textureOperations: GpuTextureOperations
    val pixelTransferOperations: GpuPixelTransferOperations
    val ssboOperations: GpuSsboOperations
    fun capabilities(): GlCapabilities.Result

    // These services are closed by the context owner.
    fun shaders(): ShaderLibrary
    fun passes(): PassGraph
}
