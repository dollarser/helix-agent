package com.helix.runtime.proot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * HXA-087: the offline legal/build-manifest page content (pure JVM; the companion
 * feeds it the parsed lock + embedded license texts) and the update-state decision.
 */
class ProotLegalContentTest {
    private val text =
        object : ProotLegalContent.Text {
            override fun pageHeader() = listOf("PRoot Runtime licenses", "Offline embedded content")

            override fun offlineNotice() =
                listOf("Offline", "No INTERNET", "Same-signature updates only", "URLs are never accessed")

            override fun manifestTitle() = "Build manifest"

            override fun fingerprint(value: String) = "Fingerprint: $value"

            override fun component(
                id: String,
                version: String,
            ) = "Component $id ($version)"

            override fun license(
                name: String,
                spdx: String,
            ) = "License: $name [$spdx]"

            override fun sourceUrl(url: String) = "Source URL: $url"

            override fun source(
                repository: String,
                ref: String,
            ) = "Source: $repository @ $ref"

            override fun size(bytes: Long) = "Size: $bytes bytes"

            override fun patches(
                count: Int,
                paths: String,
            ) = "Patches: $count ($paths)"

            override fun packages(count: Int) = "Included packages ($count):"

            override fun licenseSection(
                ref: String,
                lines: Int,
            ) = "Full license: $ref ($lines lines)"
        }

    private fun component(
        id: String,
        version: String = "1.0.0",
        spdx: String = "GPL-2.0-or-later",
        textRef: String = "licenses/$spdx.txt",
        packages: List<RuntimePackageInfo> = emptyList(),
    ): RuntimeComponent =
        RuntimeComponent(
            id = id,
            version = version,
            abi = RuntimeAbi.ARM64_V8A,
            url = "https://packages.termux.dev/apt/termux-main/pool/main/$id/$id-$version.deb",
            size = 1024,
            sha256 = "a".repeat(64),
            license = RuntimeLicense(spdx = spdx, name = "Test License $spdx", textRef = textRef),
            source = RuntimeSource(repository = "https://github.com/example/$id", ref = "v$version"),
            packages = packages,
        )

    private fun lock(vararg components: RuntimeComponent): RuntimeLock =
        RuntimeLock(lockVersion = 1, abi = RuntimeAbi.ARM64_V8A, components = components.toList())

    @Test
    fun theOfflineNoticeStatesNoNetworkAndSameSignatureUpdates() {
        val notice = ProotLegalContent.offlineNotice(text)
        val text = notice.joinToString("\n")
        assertTrue("INTERNET" in text)
        assertTrue("Same-signature" in text)
        assertTrue("Offline" in text)
        assertTrue("never accessed" in text)
    }

    @Test
    fun theBuildManifestHeaderCarriesLockIdentity() {
        val l = lock(component("proot", "5.1.107.92"))
        val header = ProotLegalContent.buildManifestHeader(l, "f".repeat(64), text).joinToString("\n")
        assertTrue("lockVersion: 1" in header)
        assertTrue("arm64-v8a" in header)
        assertTrue("f".repeat(64) in header)
    }

    @Test
    fun eachComponentBlockCarriesVersionUrlSourceAndLicense() {
        val lines =
            ProotLegalContent
                .componentLines(
                    component(
                        "proot",
                        "5.1.107.92",
                        packages =
                            listOf(
                                RuntimePackageInfo("bash", "5.2.37-r0", "GPL-3.0-or-later"),
                                RuntimePackageInfo("git", "2.49.1-r0", "GPL-2.0-only"),
                            ),
                    ),
                    text,
                ).joinToString("\n")
        assertTrue("Component proot (5.1.107.92)" in lines)
        assertTrue("https://packages.termux.dev/apt/termux-main/pool/main/proot/proot-5.1.107.92.deb" in lines)
        assertTrue("https://github.com/example/proot @ v5.1.107.92" in lines)
        assertTrue("[GPL-2.0-or-later]" in lines)
        assertTrue("- bash 5.2.37-r0 [GPL-3.0-or-later]" in lines)
        assertTrue("- git 2.49.1-r0 [GPL-2.0-only]" in lines)
    }

    @Test
    fun theFullPageAssemblesNoticeManifestComponentsAndLicenseTextsOnce() {
        val l =
            lock(
                component("proot", "1.0.0", spdx = "GPL-2.0-or-later"),
                component("alpine-rootfs", "3.22.5", spdx = "GPL-2.0-or-later"), // same textRef
                component("libtalloc", "2.4.3", spdx = "LGPL-3.0-or-later"),
            )
        val page =
            ProotLegalContent.page(
                l,
                "c".repeat(64),
                licenseTexts =
                    mapOf(
                        "licenses/GPL-2.0-or-later.txt" to "GPL-2 TEXT",
                        "licenses/LGPL-3.0-or-later.txt" to "LGPL-3 TEXT",
                    ),
                text = text,
            )
        assertTrue("PRoot Runtime licenses" in page)
        assertTrue("Offline" in page)
        assertTrue("Component alpine-rootfs (3.22.5)" in page)
        // The shared GPL-2 text appears exactly ONCE (deduped by textRef):
        assertEquals(1, page.split("GPL-2 TEXT").size - 1)
        assertTrue("LGPL-3 TEXT" in page)
        assertTrue("Full license: licenses/LGPL-3.0-or-later.txt" in page)
    }

    @Test
    fun theUpdateStateDecisionIsFailClosed() {
        assertEquals(RuntimeUpdateState.INSTALL, updateStateFor("new-sha", null))
        assertEquals(RuntimeUpdateState.UPDATE, updateStateFor("new-sha", "old-sha"))
        assertEquals(RuntimeUpdateState.REPAIR, updateStateFor("same-sha", "same-sha"))
    }

    @Test
    fun theActiveLockSha256ReadsTheActiveInstallManifest() {
        val dir = createTempDir().apply { deleteOnExit() }
        val runtimeRoot = File(dir, "runtime").apply { mkdirs() }
        val lock =
            lock(
                component("proot", "5.1.107.92"),
                component("alpine-rootfs", "3.22.5", spdx = "LGPL-3.0-or-later"),
            )
        val sha = RuntimeLockCodec.sha256Hex(lock)
        val installId = RootFsInstaller.newInstallId()
        val installDir = File(runtimeRoot, installId).apply { mkdirs() }
        File(installDir, "manifest.json").writeText(
            RuntimeManifestCodec.encodeManifest(
                RuntimeInstallManifest(
                    schemaVersion = RuntimeManifestCodec.SUPPORTED_SCHEMA_VERSION,
                    installId = installId,
                    abi = lock.abi,
                    installedAtEpochMs = 1L,
                    lockSha256 = sha,
                    lock = lock,
                    smoke = null,
                ),
            ),
        )
        InstallState.writeActivation(
            runtimeRoot,
            "active.json",
            RuntimeActivation(schemaVersion = 1, installId = installId, activatedAtEpochMs = 1L),
        )
        assertEquals(sha, RuntimeInstallQueries.activeLockSha256(runtimeRoot))
    }

    @Test
    fun theActiveLockSha256IsNullWithoutAnActiveInstall() {
        val dir = createTempDir().apply { deleteOnExit() }
        val runtimeRoot = File(dir, "runtime").apply { mkdirs() }
        assertEquals(null, RuntimeInstallQueries.activeLockSha256(runtimeRoot))
        // A corrupt active manifest is NOT silently treated as "matches": the
        // UI maps the throw to a stable refusal, never to REPAIR.
        val installId = RootFsInstaller.newInstallId()
        File(runtimeRoot, "$installId").mkdirs()
        File(runtimeRoot, "$installId/manifest.json").writeText("{ not a manifest")
        InstallState.writeActivation(
            runtimeRoot,
            "active.json",
            RuntimeActivation(schemaVersion = 1, installId = installId, activatedAtEpochMs = 1L),
        )
        org.junit.Assert.assertThrows(
            "corrupt manifest must throw, not read as up-to-date",
            RuntimeException::class.java,
        ) { RuntimeInstallQueries.activeLockSha256(runtimeRoot) }
    }

    private fun createTempDir(): File =
        java.nio.file.Files
            .createTempDirectory("proot-legal-test")
            .toFile()
}
