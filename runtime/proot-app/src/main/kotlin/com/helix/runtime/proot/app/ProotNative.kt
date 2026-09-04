package com.helix.runtime.proot.app

import java.util.concurrent.atomic.AtomicLong

/**
 * The native `getpagesize()` seam (HXA-084). No Java API exposes the host
 * kernel's page size, and the ELF pre-activation check must use the REAL value:
 * a 16 KiB device must never admit a 4 KiB-only ELF, so the conservative 4 KiB
 * baseline (the HXA-083 repair-activity constant) is replaced by this seam
 * everywhere the page size matters.
 *
 * The value is cached: getpagesize() cannot change for a booted kernel, and the
 * JNI call is then paid exactly once per process.
 */
object ProotNative {
    private const val UNKNOWN = -1L
    private val cached = AtomicLong(UNKNOWN)

    /** The host page size in bytes; never a non-positive value (callers fail closed). */
    fun pageSizeBytes(): Long {
        var value = cached.get()
        if (value == UNKNOWN) {
            value = queryNativePageSize()
            cached.compareAndSet(UNKNOWN, value)
        }
        return if (value == UNKNOWN) cached.get() else value
    }

    private fun queryNativePageSize(): Long {
        val value = nativeGetpagesize()
        if (value > 0) return value
        error("getpagesize() returned a non-positive value")
    }

    private external fun nativeGetpagesize(): Long

    init {
        System.loadLibrary("proot_native")
    }
}
