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

/** Shell-owned pixels on a host-owned display. Never mirrors, unlocks, or injects into the main display. */
@Suppress("TooGenericExceptionCaught") // Cleanup rethrows the original initialization failure.
@android.annotation.SuppressLint("PrivateApi")
internal class ShellBackgroundDisplay(
    context: Context,
    private val owner: android.os.IBinder,
) : AutoCloseable {
    val identity: String
    val recovery: RetainedTaskRecovery
    val retainedTasks: List<RetainedDisplayTask>
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
        check(android.os.Process.myUid() in setOf(0, 2000)) { "PRIVILEGED_UID_REQUIRED" }
        val shell =
            object : ContextWrapper(context) {
                override fun getPackageName() = "com.android.shell"

                override fun getOpPackageName() = "com.android.shell"

                override fun getApplicationContext(): Context = this

                @androidx.annotation.RequiresApi(31)
                override fun getAttributionSource(): android.content.AttributionSource =
                    android.content.AttributionSource
                        .Builder(android.os.Process.myUid())
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
            val retained = RetainedVirtualDisplay(manager, owner)
            identity = retained.lease.identity
            retainedTasks = retained.lease.tasks
            recovery = RetainedTaskRecovery(retainedTasks, retained.lease.displayId < 0)
            var trusted = true
            display =
                try {
                    retained.open(reader.surface, true)
                } catch (_: SecurityException) {
                    trusted = false
                    retained.open(reader.surface, false)
                }
            created = display
            ShizukuExecutionGuard.displayBound(owner, id, identity)
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
    fun preview(width: Int): ByteArray? =
        synchronized(lock) {
            val source = pixels?.takeUnless { closed } ?: return@synchronized null
            var thumbnail: Bitmap? = null
            var thumbnailWidth = 0
            try {
                encodeBackgroundPreview(width) { targetWidth, quality ->
                    if (thumbnailWidth != targetWidth) {
                        thumbnail?.takeUnless { it === source }?.recycle()
                        thumbnail = Bitmap.createScaledBitmap(source, targetWidth, HEIGHT * targetWidth / WIDTH, true)
                        thumbnailWidth = targetWidth
                    }
                    java.io.ByteArrayOutputStream().use { bytes ->
                        check(requireNotNull(thumbnail).compress(Bitmap.CompressFormat.JPEG, quality, bytes))
                        bytes.toByteArray()
                    }
                }
            } finally {
                thumbnail?.takeUnless { it === source }?.recycle()
            }
        }

    override fun close() = dispose(release = true)

    fun detach() = dispose(release = false)

    private fun dispose(release: Boolean) {
        synchronized(lock) {
            if (closed) return
            closed = true
            try {
                if (release) {
                    ShellTaskRetention.release(id, display::release)
                } else {
                    display.surface = null
                }
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
