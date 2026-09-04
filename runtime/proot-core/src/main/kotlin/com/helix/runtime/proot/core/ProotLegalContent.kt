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
    /** The stable offline notice shown at the top of the page (roadmap HXA-087 离线 notice). */
    fun offlineNotice(): List<String> =
        listOf(
            "离线声明：",
            "· PRoot Runtime 无任何网络权限（companion 包不声明 INTERNET），",
            "  执行期间不联网、不下载任何内容。",
            "· Runtime 只随同签名的 Runtime APK 更新（构建期固定资产，",
            "  设备上不在线下载 executable / RootFS / 包）。",
            "· 本页内容全部来自 APK 内嵌资产，离线可读；以下来源 URL",
            "  仅为构建期来源记录，运行时永不访问。",
        )

    /** The build-manifest header lines (lock identity + canonical fingerprint). */
    fun buildManifestHeader(
        lock: RuntimeLock,
        lockSha256: String,
    ): List<String> =
        listOf(
            "",
            "构建清单（runtime-lock.json）：",
            "· lockVersion: ${lock.lockVersion}",
            "· ABI: ${lock.abi.wire}",
            "· 规范 lock 指纹 (SHA-256): $lockSha256",
        )

    /** One component block: identity, artifact pin, source, license, packages. */
    fun componentLines(component: RuntimeComponent): List<String> {
        val lines =
            mutableListOf(
                "",
                "组件 ${component.id}（${component.version}）：",
                "· 许可证: ${component.license.name} [${component.license.spdx}]",
                "· 来源 URL: ${component.url}",
                "· 源码: ${component.source.repository} @ ${component.source.ref}",
                "· SHA-256: ${short(component.sha256)}…",
                "· 大小: ${component.size} 字节",
            )
        if (component.source.patches.isNotEmpty()) {
            lines +=
                "· 补丁: ${component.source.patches.size} 个（${component.source.patches.joinToString(", ") { it.path }}）"
        }
        if (component.packages.isNotEmpty()) {
            lines += "· 预装包 (${component.packages.size}):"
            for (pkg in component.packages) {
                lines += "    - ${pkg.name} ${pkg.version} [${pkg.licenseSpdx}]"
            }
        }
        return lines
    }

    /** A full license text section (offline; the text ships in the APK). */
    fun licenseSection(
        textRef: String,
        text: String,
    ): List<String> = listOf("", "—— 许可证全文: $textRef（${text.lineSequence().count()} 行，离线内嵌）——", text, "")

    /** Assembles the whole page: notice + manifest + components + license texts. */
    fun page(
        lock: RuntimeLock,
        lockSha256: String,
        licenseTexts: Map<String, String>,
    ): String {
        val lines =
            mutableListOf(
                "PRoot Runtime — 许可证与来源",
                "（离线页面；内容全部来自 APK 内嵌资产）",
            )
        lines += offlineNotice()
        lines += buildManifestHeader(lock, lockSha256)
        val seenTexts = mutableSetOf<String>()
        for (component in lock.components) {
            lines += componentLines(component)
            if (seenTexts.add(component.license.textRef)) {
                val text = licenseTexts[component.license.textRef]
                if (text != null) {
                    lines += licenseSection(component.license.textRef, text)
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
