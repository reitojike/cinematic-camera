package dev.cinematic.p0.photoroll

import android.content.Context
import androidx.annotation.RequiresApi
import dev.cinematic.p0.rawcapture.AndroidRenderedJpegOperation
import dev.cinematic.p0.rawcapture.CompletedRender
import dev.cinematic.p0.rawcapture.RendererPublicationContract

/**
 * For a future enrolled completed renderer only. The renderer must persist the request/generation,
 * close its private output, and retain this returned owner for every retry/recreation of that operation.
 * No production contracts, JPEG encoder, generation authority or scheduler are supplied here.
 */
@RequiresApi(29)
internal fun preparePhotoRollJpegSave(
    context: Context,
    original: PhotoRollOriginal,
    mode: PhotoRollPreviewMode,
    render: CompletedRender,
    supportedContracts: Set<RendererPublicationContract>,
    isCurrent: () -> Boolean,
): PhotoRollJpegSave = PhotoRollJpegSave.prepare(
    original.uri.toString(), original.fingerprint, mode, render, supportedContracts, isCurrent,
) {
    val operation = AndroidRenderedJpegOperation.get(context, render.request.captureId, render.request.generation)
    val publisher = operation.publisher(render, supportedContracts, isCurrent)
    PhotoRollJpegPublisher(publisher::advance)
}
