package com.helix.runtime.proot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeBaselineTest {
    private val proot =
        RuntimeComponent(
            id = "proot",
            version = "5.2.0",
            abi = RuntimeAbi.ARM64_V8A,
            url = "https://packages.example.org/proot.deb",
            size = 100L,
            sha256 = SHA,
            license = RuntimeLicense("GPL-2.0-only", "GPLv2", "licenses/GPL-2.0-only.txt"),
            source = RuntimeSource(repository = "https://github.com/termux/proot", ref = "v5.2.0"),
        )

    private fun rootfs(packages: List<RuntimePackageInfo> = REQUIRED) =
        RuntimeComponent(
            id = "alpine-rootfs",
            version = "3.20.3",
            abi = RuntimeAbi.ARM64_V8A,
            url = "https://dl-cdn.example.org/rootfs.tar.gz",
            size = 1_000L,
            sha256 = SHA,
            license = RuntimeLicense("Mixed", "Alpine", "licenses/ALPINE-README.txt"),
            source = RuntimeSource(repository = "https://alpinelinux.org", ref = "v3.20.3"),
            packages = packages,
        )

    private fun lock(components: List<RuntimeComponent>) = RuntimeLock(1, RuntimeAbi.ARM64_V8A, components)

    @Test
    fun aCompleteBaselineLockHasNoViolations() {
        val l = lock(listOf(proot, rootfs()))
        assertEquals(emptyList<String>(), RuntimeBaseline.violations(l))
        assertEquals(l, l.requireBaseline())
    }

    @Test
    fun aMissingProotComponentIsReported() {
        val violations = RuntimeBaseline.violations(lock(listOf(rootfs())))
        assertEquals(listOf("missing required component: proot"), violations)
    }

    @Test
    fun aMissingRootfsComponentIsReported() {
        val violations = RuntimeBaseline.violations(lock(listOf(proot)))
        assertEquals(listOf("missing required component: alpine-rootfs"), violations)
    }

    @Test
    fun missingRootfsPackagesAreReportedPerName() {
        val violations =
            RuntimeBaseline.violations(
                lock(listOf(proot, rootfs(REQUIRED.filterNot { it.name == "ripgrep" }))),
            )
        assertEquals(listOf("rootfs is missing required package: ripgrep"), violations)
    }

    @Test
    fun requireBaselineThrowsWithAllViolationsListed() {
        val l = lock(listOf(proot.copy(abi = RuntimeAbi.X86_64), rootfs(REQUIRED.filterNot { it.name == "git" })))
        val e = assertThrows(RuntimeLockSchemaException::class.java) { l.requireBaseline() }
        assertTrue(e.message!!.contains("component proot ABI x86_64 != lock ABI arm64-v8a"))
        assertTrue(e.message!!.contains("rootfs is missing required package: git"))
    }

    @Test
    fun aMixedAbiLockIsReported() {
        val violations = RuntimeBaseline.violations(lock(listOf(proot, rootfs().copy(abi = RuntimeAbi.X86_64))))
        assertEquals(listOf("component alpine-rootfs ABI x86_64 != lock ABI arm64-v8a"), violations)
    }

    private companion object {
        const val SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        val REQUIRED: List<RuntimePackageInfo> =
            listOf(
                RuntimePackageInfo("bash", "5.2.26-r0", "GPL-3.0-or-later"),
                RuntimePackageInfo("git", "2.45.2-r0", "GPL-2.0-only"),
                RuntimePackageInfo("python3", "3.12.4-r1", "PSF-2.0"),
                RuntimePackageInfo("nodejs", "20.15.1-r0", "MIT"),
                RuntimePackageInfo("ripgrep", "14.1.0-r0", "Unlicense"),
            )
    }
}
