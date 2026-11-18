package com.helix.app.provider

import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelFailureDiagnostic.ServerError

/** Refresh discovery evidence only; an inference error never selects a replacement or replays a turn. */
internal fun modelDirectoryRejected(error: ModelEvent.Error): Boolean =
    error.diagnostic?.let {
        it.serverError == ServerError.MODEL_NOT_FOUND ||
            (it.serverError == ServerError.PERMISSION_ERROR && it.errorParameter == "model")
    } == true
