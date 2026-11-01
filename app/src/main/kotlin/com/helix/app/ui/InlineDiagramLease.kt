package com.helix.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.webkit.WebView
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CompletableDeferred

/** Main-thread lease. Capacity is released only after the attached engine has actually been destroyed. */
internal class InlineDiagramLease(
    val html: String,
    val kind: BlockPreviewKind,
    private val canvasColor: Int,
) {
    val result = CompletableDeferred<Bitmap?>()
    var view: OfflinePreviewWebView? = null
    var active = true
    private var closed = false

    fun create(
        context: android.content.Context,
        onHeight: (Float) -> Unit,
    ): View =
        try {
            OfflinePreviewWebView(context, html, kind, onHeight) { status ->
                if (status == "error") result.complete(null)
                if (status == "ready") capture()
            }.apply {
                view = this
                fitDiagram = kind == BlockPreviewKind.MERMAID || kind == BlockPreviewKind.SVG
                setBackgroundColor(this@InlineDiagramLease.canvasColor)
                loadUrl(kind.page)
            }
        } catch (_: RuntimeException) {
            result.complete(null)
            release(view)
            View(context)
        }

    fun capture() {
        val owned = view ?: return
        owned.evaluateJavascript(
            "Math.min(1,(document.getElementById('preview').getBoundingClientRect().width+${padding()})/" +
                "document.documentElement.clientWidth)",
        ) { raw ->
            if (active && !closed && !result.isCompleted) {
                val fraction = raw.toFloatOrNull()?.takeIf { it.isFinite() && it > 0 && it <= 1 }
                if (fraction == null) result.complete(null) else awaitPixels(owned, fraction)
            }
        }
    }

    private fun padding() = if (kind == BlockPreviewKind.INLINE_FORMULA) 0 else 24

    private fun awaitPixels(
        owned: OfflinePreviewWebView,
        fraction: Float,
    ) {
        // Let Compose apply the measured height before waiting for WebView's visual update.
        owned.postOnAnimation {
            owned.postOnAnimation {
                if (active && !closed) {
                    owned.postVisualStateCallback(
                        0,
                        object : WebView.VisualStateCallback() {
                            override fun onComplete(requestId: Long) {
                                if (active && !closed && !result.isCompleted) result.complete(snapshot(owned, fraction))
                            }
                        },
                    )
                }
            }
        }
    }

    private fun snapshot(
        owned: OfflinePreviewWebView,
        fraction: Float,
    ): Bitmap? {
        if (owned.width <= 0 || owned.height <= 0) return null
        val width =
            kotlin.math
                .ceil(owned.width * fraction.toDouble())
                .toInt()
                .coerceIn(1, owned.width)
        val pixels = width.toDouble() * owned.height
        val scale = minOf(1.0, kotlin.math.sqrt(2_000_000.0 / pixels), 2048.0 / width)
        return try {
            val bitmap =
                createBitmap(
                    (width * scale).toInt().coerceAtLeast(1),
                    (owned.height * scale).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                )
            val canvas = Canvas(bitmap)
            canvas.scale(scale.toFloat(), scale.toFloat())
            owned.draw(canvas)
            bitmap
        } catch (_: OutOfMemoryError) {
            InlineDiagramCache.clear()
            null
        }
    }

    fun abandon() {
        active = false
        if (view == null) release(null)
    }

    fun release(released: View?) {
        if (closed) return
        closed = true
        active = false
        (released as? OfflinePreviewWebView)?.release()
        view = null
        InlineDiagramCache.renderer.release()
    }
}
