package com.helix.runtime.proot.app

import android.content.Context
import com.helix.runtime.proot.core.ProotLegalContent
import com.helix.runtime.proot.core.RuntimeLock
import com.helix.runtime.proot.core.RuntimeLockCodec

/**
 * The companion-side adapter for the offline legal page (HXA-087). Reads the
 * embedded lock + license texts from THIS APK's assets (single source of truth —
 * the main app never duplicates them) and hands the rendered page to
 * [ProotLegalActivity]. Everything is local: the page works fully offline and the
 * source URLs it prints are build metadata, never fetched.
 */
object ProotLegalPage {
    /** The embedded license texts keyed by the lock's `textRef` (relative, no traversal). */
    fun licenseTexts(
        context: Context,
        lock: RuntimeLock,
    ): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (component in lock.components) {
            val ref = component.license.textRef
            if (ref in out) continue
            val text =
                runCatching {
                    context.assets
                        .open("runtime/$ref")
                        .bufferedReader()
                        .use { it.readText() }
                }.getOrNull()
            if (text != null) {
                out[ref] = text
            }
        }
        return out
    }

    /** The full page text; throws only for a structurally broken embedded lock. */
    fun build(
        context: Context,
        lock: RuntimeLock,
    ): String {
        val lockSha = RuntimeLockCodec.sha256Hex(lock)
        return ProotLegalContent.page(lock, lockSha, licenseTexts(context, lock))
    }
}
