package com.helix.runtime.proot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeLockCodecTest {
    private val prootComponent =
        RuntimeComponent(
            id = "proot",
            version = "5.2.0-12-gabc123",
            abi = RuntimeAbi.ARM64_V8A,
            url = "https://packages.example.org/pool/proot/proot_5.2.0_arm64.deb",
            size = 120_000L,
            sha256 = SHA_A,
            license =
                RuntimeLicense(
                    "GPL-2.0-only",
                    "GNU General Public License v2.0 only",
                    "licenses/GPL-2.0-only.txt",
                ),
            source =
                RuntimeSource(
                    repository = "https://github.com/termux/proot",
                    ref = "v5.2.0-termux-20260801",
                    patches =
                        listOf(
                            RuntimePatch(
                                path = "patches/proot-0001-android-tls.patch",
                                url = "https://github.com/termux/proot/raw/v5.2.0-termux-20260801/patches/0001.patch",
                                size = 2_048L,
                                sha256 = SHA_B,
                            ),
                        ),
                ),
        )

    private val rootfsComponent =
        RuntimeComponent(
            id = "alpine-rootfs",
            version = "3.20.3",
            abi = RuntimeAbi.ARM64_V8A,
            url = "https://dl-cdn.example.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz",
            size = 3_200_000L,
            sha256 = SHA_C,
            license = RuntimeLicense("Mixed", "Alpine Linux (per-package licenses)", "licenses/ALPINE-README.txt"),
            source = RuntimeSource(repository = "https://alpinelinux.org", ref = "v3.20.3"),
            packages =
                listOf(
                    RuntimePackageInfo("bash", "5.2.26-r0", "GPL-3.0-or-later"),
                    RuntimePackageInfo("git", "2.45.2-r0", "GPL-2.0-only"),
                    RuntimePackageInfo("python3", "3.12.4-r1", "PSF-2.0"),
                    RuntimePackageInfo("nodejs", "20.15.1-r0", "MIT"),
                    RuntimePackageInfo("ripgrep", "14.1.0-r0", "Unlicense"),
                ),
        )

    private fun baselineLock() = RuntimeLock(1, RuntimeAbi.ARM64_V8A, listOf(prootComponent, rootfsComponent))

    @Test
    fun aValidLockParsesIntoTheFullModel() {
        val parsed = RuntimeLockCodec.parse(RuntimeLockCodec.encode(baselineLock()))
        assertEquals(baselineLock(), parsed)
        assertEquals(RuntimeAbi.ARM64_V8A, parsed.abi)
        assertEquals(2, parsed.components.size)
        assertEquals(5, parsed.component("alpine-rootfs")?.packages?.size)
        assertEquals(
            1,
            parsed
                .component("proot")
                ?.source
                ?.patches
                ?.size,
        )
        assertEquals("GPL-2.0-only", parsed.component("proot")?.license?.spdx)
    }

    @Test
    fun parseIsIndependentOfInputKeyOrder() {
        val lock = RuntimeLock(1, RuntimeAbi.ARM64_V8A, listOf(prootComponent))
        // The same document hand-ordered with every object's keys reversed: parsing must yield
        // the identical model, and the canonical encode must normalize it byte-for-byte.
        // Reorder the canonical encoding's keys (top level AND the first component) via exact
        // string surgery: parsing must yield the identical model, and the canonical encode must
        // normalize it byte-for-byte (and hash-for-hash) back to the declared order.
        val encoded = RuntimeLockCodec.encode(lock)
        val reordered =
            encoded
                .removePrefix("{\"lockVersion\":1,\"abi\":\"arm64-v8a\",")
                .dropLast(1)
                .let { "{" + it + ",\"abi\":\"arm64-v8a\",\"lockVersion\":1}" }
                .replaceFirst(
                    "\"id\":\"proot\",\"version\":\"5.2.0-12-gabc123\",\"abi\":\"arm64-v8a\"",
                    "\"abi\":\"arm64-v8a\",\"id\":\"proot\",\"version\":\"5.2.0-12-gabc123\"",
                )
        assertNotEquals(encoded, reordered)
        assertEquals(lock, RuntimeLockCodec.parse(reordered))
        assertEquals(encoded, RuntimeLockCodec.encode(RuntimeLockCodec.parse(reordered)))
        assertEquals(
            RuntimeLockCodec.sha256Hex(lock),
            RuntimeLockCodec.sha256Hex(RuntimeLockCodec.parse(reordered)),
        )
    }

    @Test
    fun encodeIsCanonicalAndStableAcrossRoundTrips() {
        val lock = baselineLock()
        val first = RuntimeLockCodec.encode(lock)
        val second = RuntimeLockCodec.encode(RuntimeLockCodec.parse(first))
        assertEquals(first, second)
        assertEquals(RuntimeLockCodec.sha256Hex(lock), RuntimeLockCodec.sha256Hex(RuntimeLockCodec.parse(second)))
        assertEquals(64, RuntimeLockCodec.sha256Hex(lock).length)
    }

    @Test
    fun unknownTopLevelKeyIsRejected() {
        val encoded = RuntimeLockCodec.encode(baselineLock())
        val tampered = encoded.dropLast(1) + ",\"note\":\"x\"}"
        val e = assertThrows(RuntimeLockSchemaException::class.java) { RuntimeLockCodec.parse(tampered) }
        assertTrue(e.message!!.contains("note"))
    }

    @Test
    fun unknownComponentKeyIsRejected() {
        val tampered =
            RuntimeLockCodec
                .encode(baselineLock())
                .replaceFirst("\"version\":\"5.2.0-12-gabc123\"", "\"version\":\"5.2.0-12-gabc123\",\"extra\":1")
        assertThrows(RuntimeLockSchemaException::class.java) { RuntimeLockCodec.parse(tampered) }
    }

    @Test
    fun missingRequiredComponentFieldIsRejected() {
        val tampered = RuntimeLockCodec.encode(baselineLock()).replaceFirst("\"sha256\":\"$SHA_A\",", "")
        assertThrows(RuntimeLockSchemaException::class.java) { RuntimeLockCodec.parse(tampered) }
    }

    @Test
    fun unsupportedLockVersionIsRejected() {
        listOf("0", "2").forEach { version ->
            val tampered =
                RuntimeLockCodec.encode(baselineLock()).replaceFirst("\"lockVersion\":1", "\"lockVersion\":$version")
            val e =
                assertThrows(RuntimeLockSchemaException::class.java) { RuntimeLockCodec.parse(tampered) }
            assertTrue(e.message!!.contains("lockVersion"))
        }
    }

    @Test
    fun unknownAbiIsRejected() {
        val tampered =
            RuntimeLockCodec.encode(baselineLock()).replaceFirst("\"abi\":\"arm64-v8a\"", "\"abi\":\"armv7\"")
        assertThrows(RuntimeLockSchemaException::class.java) { RuntimeLockCodec.parse(tampered) }
    }

    @Test
    fun x8664AbiParses() {
        val lock =
            RuntimeLock(
                1,
                RuntimeAbi.X86_64,
                listOf(
                    prootComponent.copy(abi = RuntimeAbi.X86_64),
                    rootfsComponent.copy(abi = RuntimeAbi.X86_64),
                ),
            )
        assertEquals(lock, RuntimeLockCodec.parse(RuntimeLockCodec.encode(lock)))
    }

    @Test
    fun sha256MustBeCanonicalLowercaseHex64() {
        val lock = baselineLock()
        val upper =
            RuntimeLockCodec
                .encode(lock)
                .replaceFirst("\"sha256\":\"$SHA_A\"", "\"sha256\":\"${SHA_A.uppercase()}\"")
        assertThrows(RuntimeLockSchemaException::class.java) { RuntimeLockCodec.parse(upper) }

        val shortHash =
            RuntimeLockCodec
                .encode(lock)
                .replaceFirst("\"sha256\":\"$SHA_A\"", "\"sha256\":\"${SHA_A.dropLast(1)}\"")
        assertThrows(RuntimeLockSchemaException::class.java) { RuntimeLockCodec.parse(shortHash) }
    }

    @Test
    fun urlsMustBeHttpsHostOnly() {
        fun parseWith(url: String) {
            val lock = baselineLock().copy(components = listOf(prootComponent.copy(url = url)))
            val e =
                assertThrows(RuntimeLockSchemaException::class.java) {
                    RuntimeLockCodec.parse(RuntimeLockCodec.encode(lock))
                }
            assertTrue(e.message!!.contains("url"))
        }
        parseWith("http://packages.example.org/proot.deb")
        parseWith("https://user:pass@packages.example.org/proot.deb")
        parseWith("https://192.168.1.10/proot.deb")
        parseWith("https://packages.example.org/proot.deb#v1")
    }

    @Test
    fun sizeMustBeAPositiveBoundedInteger() {
        listOf("0", "-5", "1099511627777").forEach { size ->
            val lock = baselineLock().copy(components = listOf(prootComponent.copy(size = size.toLong())))
            val e =
                assertThrows(RuntimeLockSchemaException::class.java) {
                    RuntimeLockCodec.parse(RuntimeLockCodec.encode(lock))
                }
            assertTrue(e.message!!.contains("size"))
        }
        // A fractional size is not an integer literal and is rejected.
        val tampered =
            RuntimeLockCodec.encode(baselineLock()).replaceFirst("\"size\":120000", "\"size\":120000.5")
        assertThrows(RuntimeLockSchemaException::class.java) { RuntimeLockCodec.parse(tampered) }
    }

    @Test
    fun duplicateComponentIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeLock(1, RuntimeAbi.ARM64_V8A, listOf(prootComponent, prootComponent.copy(version = "x")))
        }
    }

    @Test
    fun emptyComponentListIsRejected() {
        val e = assertThrows(IllegalArgumentException::class.java) { RuntimeLock(1, RuntimeAbi.ARM64_V8A, emptyList()) }
        assertTrue(e.message!!.contains("at least one"))
    }

    @Test
    fun embeddedRefsMustBeRelativeWithoutTraversal() {
        listOf(
            "licenses/../../etc/passwd",
            "/licenses/GPL.txt",
            "licenses\\GPL.txt",
            "a//b.txt",
            "a/./b.txt",
            "a/../b.txt",
        ).forEach {
            val lock =
                baselineLock().copy(
                    components =
                        listOf(prootComponent.copy(license = prootComponent.license.copy(textRef = it))),
                )
            val e =
                assertThrows(RuntimeLockSchemaException::class.java) {
                    RuntimeLockCodec.parse(RuntimeLockCodec.encode(lock))
                }
            assertTrue(e.message!!.contains("textRef"))
        }
    }

    @Test
    fun patchPathsMustBeRelativeWithoutTraversal() {
        val badPatch =
            prootComponent.copy(
                source =
                    prootComponent.source.copy(
                        patches =
                            listOf(
                                RuntimePatch(
                                    path = "../patches/x.patch",
                                    url = "https://example.org/x.patch",
                                    size = 1L,
                                    sha256 = SHA_B,
                                ),
                            ),
                    ),
            )
        val e =
            assertThrows(RuntimeLockSchemaException::class.java) {
                RuntimeLockCodec.parse(
                    RuntimeLockCodec.encode(
                        baselineLock().copy(components = listOf(badPatch)),
                    ),
                )
            }
        assertTrue(e.message!!.contains("path"))
    }

    @Test
    fun duplicatePatchPathsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            val patch = prootComponent.source.patches.single()
            prootComponent.copy(source = prootComponent.source.copy(patches = listOf(patch, patch)))
        }
    }

    @Test
    fun duplicatePackageNamesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            val pkg = rootfsComponent.packages.first()
            rootfsComponent.copy(packages = listOf(pkg, pkg))
        }
    }

    @Test
    fun nonObjectDocumentsAreRejected() {
        listOf("[1,2]", "\"str\"", "42", "null", "", "   ", "not json").forEach {
            assertThrows(RuntimeLockSchemaException::class.java) { RuntimeLockCodec.parse(it) }
        }
    }

    @Test
    fun aComponentWithoutPackagesIsLegalAndCanonical() {
        val component = rootfsComponent.copy(packages = emptyList())
        val lock = baselineLock().copy(components = listOf(component))
        val encoded = RuntimeLockCodec.encode(lock)
        assertTrue(encoded.contains("\"packages\":[]"))
        assertEquals(lock, RuntimeLockCodec.parse(encoded))
    }

    @Test
    fun componentLookupReturnsNullForUnknownId() {
        assertEquals(null, baselineLock().component("nope"))
        assertEquals(prootComponent, baselineLock().component("proot"))
    }

    private companion object {
        const val SHA_A = "3a7bd3e2360a3d29eea436fcfb7e44c735d117c42d1c1835420b6b9942dd4f1b"
        const val SHA_B = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        const val SHA_C = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
    }
}
