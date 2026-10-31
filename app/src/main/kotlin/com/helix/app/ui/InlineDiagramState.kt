package com.helix.app.ui

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

internal data class InlineDiagramInput(
    val source: String,
    val kind: BlockPreviewKind,
    val background: Int,
    val foreground: Int,
    val font: Float,
    val width: Int,
    val density: Float,
) {
    val key =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest("$kind|$background|$foreground|$font|$width|$density|$source".toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun html() =
        kind.html(
            source,
            background,
            foreground,
            (16f * font).coerceIn(10f, 64f),
            UUID.randomUUID().toString().replace("-", ""),
        )
}

/** One source/theme/width generation; a late result can only update this generation. */
internal class InlineDiagramState(
    initialHeight: Float = 96f,
) {
    var bitmap by mutableStateOf<Bitmap?>(null)
    var failed by mutableStateOf(false)
    var attempt by mutableIntStateOf(0)
    var render by mutableStateOf<InlineDiagramLease?>(null)
    var height by mutableFloatStateOf(initialHeight)

    suspend fun load(
        input: InlineDiagramInput,
        enabled: Boolean,
    ) {
        val cached = InlineDiagramCache.get(input.key)
        when {
            !enabled -> bitmap = null
            failed || bitmap != null -> Unit
            cached != null -> show(input, cached)
            !input.kind.valid(input.source) -> failed = true
            else -> render(input)
        }
    }

    private suspend fun render(input: InlineDiagramInput) {
        val html = input.html()
        InlineDiagramCache.renderer.acquire()
        val lease = InlineDiagramLease(html, input.kind, input.background)
        try {
            render = lease
            val result = withTimeoutOrNull(input.kind.timeoutMillis) { lease.result.await() }
            if (result == null) {
                failed = true
            } else {
                InlineDiagramCache.put(input.key, result)
                show(input, result)
            }
        } finally {
            lease.abandon()
            if (render === lease) render = null
        }
    }

    private fun show(
        input: InlineDiagramInput,
        image: Bitmap,
    ) {
        height = (image.height.toFloat() / image.width * input.width / input.density).coerceIn(64f, 360f)
        bitmap = image
    }

    fun retry() {
        failed = false
        attempt++
    }
}
