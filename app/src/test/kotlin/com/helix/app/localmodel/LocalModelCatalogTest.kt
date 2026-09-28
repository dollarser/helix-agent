package com.helix.app.localmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class LocalModelCatalogTest {
    @Test fun catalogPinsContentIdentityAndBothPublicSources() {
        assertEquals(2, LocalModelCatalog.entries.size)
        LocalModelCatalog.entries.forEach { entry ->
            assertTrue(entry.sha256.matches(Regex("[a-f0-9]{64}")))
            assertTrue(entry.sizeBytes > 0)
            assertEquals("Apache-2.0", entry.license)
            assertEquals(LocalModelCatalogSource.entries.toSet(), entry.locations.map { it.source }.toSet())
            entry.locations.forEach { location ->
                assertTrue(location.revision.matches(Regex("[a-f0-9]{40}")))
                assertTrue(location.downloadUrl.startsWith("https://"))
                assertTrue(location.downloadUrl.contains("/${location.revision}/${entry.fileName}"))
            }
        }
        val small = LocalModelCatalog.entry("qwen3-0.6b-q4-k-m")
        val large = LocalModelCatalog.entry("qwen3-4b-instruct-2507-q4-k-m")
        assertNotEquals(small.sha256, large.sha256)
        assertEquals(LocalModelCatalogEvidence.COMPATIBILITY_ONLY, small.evidence)
        assertEquals(LocalModelCatalogEvidence.FIXED_TASK_PASSED, large.evidence)
    }

    @Test fun sourcePoliciesRejectCleartextAndArbitraryRedirects() {
        val manual = LocalModelDownloadPolicy()
        manual.validateInitial(URI("https://example.com/model.gguf"))
        assertThrows(IllegalArgumentException::class.java) {
            manual.validateRedirect(URI("https://example.com/other.gguf"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            manual.validateInitial(URI("http://example.com/model.gguf"))
        }

        val ms = LocalModelCatalog.entries.first().location(LocalModelCatalogSource.MODELSCOPE)
        val msPolicy = LocalModelDownloadPolicy(redirectHostSuffixes = ms.redirectHostSuffixes)
        msPolicy.validateRedirect(URI("https://cdn-lfs-cn-1.modelscope.cn/object"))
        assertThrows(IllegalArgumentException::class.java) {
            msPolicy.validateRedirect(URI("https://modelscope.cn.evil.example/object"))
        }

        val hf = LocalModelCatalog.entries.first().location(LocalModelCatalogSource.HUGGING_FACE)
        val hfPolicy = LocalModelDownloadPolicy(redirectHostSuffixes = hf.redirectHostSuffixes)
        hfPolicy.validateRedirect(URI("https://cas-bridge.xethub.hf.co/object"))
        assertFalse(hf.redirectHostSuffixes.contains("example.com"))
    }

    @Test fun diskPreflightAccountsForTransferAndVerifiedPublishOverlap() {
        val reserve = LocalModelInstallSpace.FREE_SPACE_RESERVE_BYTES
        assertEquals(200L + reserve, LocalModelInstallSpace.additionalBytesRequired(100, 0))
        assertEquals(150L + reserve, LocalModelInstallSpace.additionalBytesRequired(100, 50))
        assertEquals(100L + reserve, LocalModelInstallSpace.additionalBytesRequired(100, 100))
        assertThrows(IllegalArgumentException::class.java) { LocalModelInstallSpace.additionalBytesRequired(100, 101) }
    }
}
