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
        val text =
            object : ProotLegalContent.Text {
                override fun pageHeader() =
                    listOf(
                        context.getString(R.string.proot_legal_page_title),
                        context.getString(R.string.proot_legal_page_subtitle),
                    )

                override fun offlineNotice() =
                    context.resources.getStringArray(R.array.proot_legal_offline_notice).toList()

                override fun manifestTitle() = context.getString(R.string.proot_legal_manifest_title)

                override fun fingerprint(value: String) = context.getString(R.string.proot_legal_fingerprint, value)

                override fun component(
                    id: String,
                    version: String,
                ) = context.getString(R.string.proot_legal_component, id, version)

                override fun license(
                    name: String,
                    spdx: String,
                ) = context.getString(R.string.proot_legal_license, name, spdx)

                override fun sourceUrl(url: String) = context.getString(R.string.proot_legal_source_url, url)

                override fun source(
                    repository: String,
                    ref: String,
                ) = context.getString(R.string.proot_legal_source, repository, ref)

                override fun size(bytes: Long) = context.getString(R.string.proot_legal_size, bytes)

                override fun patches(
                    count: Int,
                    paths: String,
                ) = context.getString(R.string.proot_legal_patches, count, paths)

                override fun packages(count: Int) = context.getString(R.string.proot_legal_packages, count)

                override fun licenseSection(
                    ref: String,
                    lines: Int,
                ) = context.getString(R.string.proot_legal_license_section, ref, lines)
            }
        return ProotLegalContent.page(lock, lockSha, licenseTexts(context, lock), text)
    }
}
