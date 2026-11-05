package com.helix.feature.browser

import android.content.ComponentCallbacks2
import java.lang.ref.WeakReference

/** Owns only the current Activity binding; logical tabs and durable metadata stay in the controller. */
internal class BrowserViewBinding(
    private val invalidated: () -> Unit,
) {
    private var reference = WeakReference<BrowserViewOwner>(null)
    val current: BrowserViewOwner? get() = reference.get()

    fun attach(owner: BrowserViewOwner) {
        check(owner.available) { "browser owner is destroyed" }
        val previous = current
        if (previous === owner) return
        reference = WeakReference(owner)
        previous?.destroy()
        invalidated()
    }

    fun detach(owner: BrowserViewOwner) {
        if (current !== owner) {
            owner.destroy()
        } else {
            reference.clear()
            owner.destroy()
            invalidated()
        }
    }

    fun pause(owner: BrowserViewOwner?) {
        if (owner === current) owner?.pause()
    }

    fun resume(owner: BrowserViewOwner?) {
        if (owner === current) owner?.resume()
    }

    fun clear() {
        current?.clear()
        invalidated()
    }

    @Suppress("DEPRECATION") // Older supported Android versions still deliver these reclaim signals.
    fun trimMemory(level: Int) {
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            clear()
        }
    }
}
