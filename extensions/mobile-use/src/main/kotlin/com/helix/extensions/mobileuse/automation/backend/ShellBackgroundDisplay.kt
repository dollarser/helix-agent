package com.helix.extensions.mobileuse.automation.backend

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.helix.extensions.mobileuse.automation.backgroundDisplayFlags
import java.util.UUID

/** Shell-owned display and pixels. Never mirrors, unlocks, or injects into the main display. */
@Suppress("TooGenericExceptionCaught") // Cleanup rethrows the original initialization failure.
@android.annotation.SuppressLint("PrivateApi")
internal class ShellBackgroundDisplay(
    context: Context,
) : AutoCloseable {
    val identity: String = UUID.randomUUID().toString()
    private val thread = HandlerThread("helix-background-frames").apply { start() }
    private val reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 3)
    private val lock = Any()
    private var pixels: Bitmap? = null

    @Volatile private var closed = false
    private val handler = Handler(thread.looper)
    private var pending = false
    private var lastRead = 0L
    private val display: VirtualDisplay
    val valid: Boolean get() = !closed && display.display.isValid
    val id: Int get() = display.display.displayId
    val independentFocus: Boolean

    init {
        check(android.os.Process.myUid() == 2000) { "SHELL_UID_REQUIRED" }
        val shell =
            object : ContextWrapper(context) {
                override fun getPackageName() = "com.android.shell"

                override fun getOpPackageName() = "com.android.shell"

                override fun getApplicationContext(): Context = this

                @androidx.annotation.RequiresApi(31)
                override fun getAttributionSource(): android.content.AttributionSource =
                    android.content.AttributionSource
                        .Builder(2000)
                        .setPackageName("com.android.shell")
                        .build()
            }
        var created: VirtualDisplay? = null
        try {
            val manager =
                DisplayManager::class.java
                    .getDeclaredConstructor(Context::class.java)
                    .apply { isAccessible = true }
                    .newInstance(shell)
            var trusted = true
            display =
                try {
                    create(manager, true)
                } catch (_: SecurityException) {
                    trusted = false
                    create(manager, false)
                }
            created = display
            check(id > 0) { "BACKGROUND_DISPLAY_INVALID" }
            val focusMask = (1 shl 11) or (1 shl 12)
            independentFocus = trusted && Build.VERSION.SDK_INT >= 34 &&
                display.display.flags and focusMask == focusMask
            reader.setOnImageAvailableListener({ schedule() }, handler)
        } catch (failure: Throwable) {
            try {
                created?.release()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            } finally {
                reader.close()
                thread.quitSafely()
            }
            throw failure
        }
    }

    private fun create(
        manager: DisplayManager,
        trusted: Boolean,
    ): VirtualDisplay =
        checkNotNull(
            manager.createVirtualDisplay(
                "Helix background $identity",
                WIDTH,
                HEIGHT,
                320,
                reader.surface,
                backgroundDisplayFlags(Build.VERSION.SDK_INT, trusted),
            ),
        ) { "BACKGROUND_DISPLAY_UNAVAILABLE" }

    private fun schedule() {
        if (pending || closed) return
        pending = true
        val remaining = (lastRead + 100 - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0)
        handler.postDelayed({
            pending = false
            lastRead = android.os.SystemClock.uptimeMillis()
            drain()
        }, remaining)
    }

    private fun drain() {
        synchronized(lock) {
            if (closed) return
            val image = reader.acquireLatestImage() ?: return
            image.use {
                val plane = it.planes.single()
                val paddedWidth = plane.rowStride / plane.pixelStride
                val bitmap = Bitmap.createBitmap(paddedWidth, HEIGHT, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.copyPixelsFromBuffer(plane.buffer)
                    pixels?.recycle()
                    pixels = Bitmap.createBitmap(bitmap, 0, 0, WIDTH, HEIGHT)
                } finally {
                    if (pixels !== bitmap) bitmap.recycle()
                }
            }
        }
    }

    fun screenshot(): Bitmap? =
        synchronized(lock) {
            pixels?.takeUnless { closed }?.copy(Bitmap.Config.ARGB_8888, false)
        }

    /** Local viewing only. Copies the cached frame without observing or changing the input target. */
    fun preview(): ByteArray? =
        synchronized(lock) {
            val source = pixels?.takeUnless { closed } ?: return@synchronized null
            val thumbnail = Bitmap.createScaledBitmap(source, WIDTH / 3, HEIGHT / 3, true)
            try {
                java.io.ByteArrayOutputStream().use { bytes ->
                    check(thumbnail.compress(Bitmap.CompressFormat.JPEG, 75, bytes))
                    bytes.toByteArray().takeIf { it.size <= BackgroundPreviewTransport.MAX_BYTES }
                }
            } finally {
                thumbnail.recycle()
            }
        }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            try {
                ShellTaskRetention.release(id, display::release)
            } finally {
                handler.removeCallbacksAndMessages(null)
                reader.setOnImageAvailableListener(null, null)
                reader.close()
                pixels?.recycle()
                pixels = null
                thread.quitSafely()
            }
        }
        thread.join(2000)
        check(!thread.isAlive) { "BACKGROUND_FRAME_THREAD_DID_NOT_EXIT" }
    }

    private companion object {
        const val WIDTH = 720
        const val HEIGHT = 1280
    }
}
