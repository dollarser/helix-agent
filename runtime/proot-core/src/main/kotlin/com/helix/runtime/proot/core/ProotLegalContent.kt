package com.helix.runtime.proot.core

/**
 * Builds the OFFLINE legal/build-manifest page content (HXA-087, architecture doc
 * local-code-execution section 9: 所有第三方许可证和源码信息可离线查看).
 *
 * Pure JVM: the Android companion feeds it the parsed [RuntimeLock], the canonical
 * lock fingerprint and the embedded license texts keyed by the lock's `textRef`
 * (relative paths under the APK's `licenses/` directory). The source URLs in the
 * lock are DISPLAYED as build metadata — this page never fetches anything (the
 * companion holds no INTERNET permission; the page must work offline).
 */
object ProotLegalContent {
    @Suppress("TooManyFunctions") // Each method is one locale-owned legal-page sentence shape.
    interface Text {
        fun pageHeader(): List<String>

        fun offlineNotice(): List<String>

        fun manifestTitle(): String

        fun fingerprint(value: String): String

        fun component(
            id: String,
            version: String,
        ): String

        fun license(
            name: String,
            spdx: String,
        ): String

        fun sourceUrl(url: String): String

        fun source(
            repository: String,
            ref: String,
        ): String

        fun size(bytes: Long): String

        fun patches(
            count: Int,
            paths: String,
        ): String

        fun packages(count: Int): String

        fun licenseSection(
            ref: String,
            lines: Int,
        ): String
    }

    /** The stable offline notice shown at the top of the page (roadmap HXA-087 离线 notice). */
    fun offlineNotice(text: Text): List<String> = text.offlineNotice()

    /** The build-manifest header lines (lock identity + canonical fingerprint). */
    fun buildManifestHeader(
        lock: RuntimeLock,
        lockSha256: String,
        text: Text,
    ): List<String> =
        listOf(
            "",
            text.manifestTitle(),
            "· lockVersion: ${lock.lockVersion}",
            "· ABI: ${lock.abi.wire}",
            text.fingerprint(lockSha256),
        )

    /** One component block: identity, artifact pin, source, license, packages. */
    fun componentLines(
        component: RuntimeComponent,
        text: Text,
    ): List<String> {
        val lines =
            mutableListOf(
                "",
                text.component(component.id, component.version),
                text.license(component.license.name, component.license.spdx),
                text.sourceUrl(component.url),
                text.source(component.source.repository, component.source.ref),
                "· SHA-256: ${short(component.sha256)}…",
                text.size(component.size),
            )
        if (component.source.patches.isNotEmpty()) {
            lines +=
                text.patches(
                    component.source.patches.size,
                    component.source.patches.joinToString(", ") { it.path },
                )
        }
        if (component.packages.isNotEmpty()) {
            lines += text.packages(component.packages.size)
            for (pkg in component.packages) {
                lines += "    - ${pkg.name} ${pkg.version} [${pkg.licenseSpdx}]"
            }
        }
        return lines
    }

    /** A full license text section (offline; the text ships in the APK). */
    fun licenseSection(
        textRef: String,
        licenseText: String,
        labels: Text,
    ): List<String> = listOf("", labels.licenseSection(textRef, licenseText.lineSequence().count()), licenseText, "")

    /** Assembles the whole page: notice + manifest + components + license texts. */
    fun page(
        lock: RuntimeLock,
        lockSha256: String,
        licenseTexts: Map<String, String>,
        text: Text,
    ): String {
        val lines = text.pageHeader().toMutableList()
        lines += offlineNotice(text)
        lines += buildManifestHeader(lock, lockSha256, text)
        val seenTexts = mutableSetOf<String>()
        for (component in lock.components) {
            lines += componentLines(component, text)
            if (seenTexts.add(component.license.textRef)) {
                val licenseText = licenseTexts[component.license.textRef]
                if (licenseText != null) {
                    lines += licenseSection(component.license.textRef, licenseText, labels = text)
                }
            }
        }
        return lines.joinToString("\n")
    }

    private fun short(hex: String): String = hex.take(16)
}

/** The update state of an installed runtime relative to the APK's embedded lock. */
enum class RuntimeUpdateState {
    /** No active install: the button offers a fresh install. */
    INSTALL,

    /** Active install's lock differs from the embedded lock: the APK was updated. */
    UPDATE,

    /** Active install matches the embedded lock: reinstall = repair only. */
    REPAIR,
}

/**
 * Pure decision (HXA-087 同签名 APK 更新): compares the ACTIVE install's embedded-lock
 * fingerprint against the APK's embedded lock. Fail-closed: a null active sha (no active
 * install or unreadable manifest) reads as INSTALL, never as "up to date".
 */
fun updateStateFor(
    embeddedLockSha256: String,
    activeLockSha256: String?,
): RuntimeUpdateState =
    when {
        activeLockSha256 == null -> RuntimeUpdateState.INSTALL
        activeLockSha256 != embeddedLockSha256 -> RuntimeUpdateState.UPDATE
        else -> RuntimeUpdateState.REPAIR
    }
