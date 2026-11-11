package dev.cinematic.p0.photoroll

import dev.cinematic.p0.photoedit.PhotoEditValues

/** Only an actual successful After presentation can confirm a committed recipe. */
internal fun gpuPresentedEditor(editor: TrialStrengthEditor, presented: PhotoEditValues?, failed: Boolean): TrialStrengthEditor =
    if (!failed && presented == editor.requestedValues) editor.shown(editor.ticket, editor.requestedValues)
    else editor.showingDraft()
