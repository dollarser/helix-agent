package com.helix.app.knowledge

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler

/** A failed background load remains failed and visible; it must not crash the whole application. */
internal fun knowledgeStorageFailure(onFailure: () -> Unit) =
    CoroutineExceptionHandler { _, failure ->
        Log.w("HelixKnowledge", "Document background operation failed: ${failure.javaClass.simpleName}")
        onFailure()
    }
