package dev.cinematic.p0.photoroll

/** Only an editor that explicitly closed its accepted preview may continue its retained save. */
internal fun gpuSaveCanContinueWithoutPreview(phase: PhotoRollSavePhase?) = phase in setOf(
    PhotoRollSavePhase.GPU_FAILED, PhotoRollSavePhase.CANCELLED, PhotoRollSavePhase.PUBLISHED, PhotoRollSavePhase.UNCONFIRMED)

/** Never reopen GPU input while a save is active or its owners/publication remain unconfirmed. */
internal fun gpuSaveCanResumePreview(phase: PhotoRollSavePhase?) = phase in setOf(
    PhotoRollSavePhase.READY, PhotoRollSavePhase.PUBLISHED, PhotoRollSavePhase.CANCELLED)

/** A recreated editor may recover its handle, but may not display over an unresolved save. */
internal fun gpuSaveMustPauseLoadedPreview(phase: PhotoRollSavePhase) = phase !in setOf(
    PhotoRollSavePhase.READY, PhotoRollSavePhase.PUBLISHED, PhotoRollSavePhase.CANCELLED)
