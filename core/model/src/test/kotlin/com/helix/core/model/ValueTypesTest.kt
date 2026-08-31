package com.helix.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ValueTypesTest {
    private val validSha = "a".repeat(63) + "f"

    @Test
    fun sha256AcceptsLowercaseHex() {
        val sha = Sha256(validSha)
        assertEquals(validSha, sha.hex)
        assertEquals(validSha, sha.toString())
        assertEquals(Sha256(validSha), sha)
    }

    @Test
    fun sha256FromHexNormalizesUppercase() {
        val upper = validSha.uppercase()
        assertEquals(Sha256(validSha), Sha256.fromHex(upper))
        assertEquals(Sha256(validSha), Sha256.fromHex(validSha))
        // Mixed case also normalizes.
        val mixed = "A".repeat(32) + "f".repeat(32)
        assertEquals(Sha256(mixed.lowercase()), Sha256.fromHex(mixed))
    }

    @Test
    fun sha256RejectsInvalidInput() {
        assertThrows<IllegalArgumentException> { Sha256("") }
        assertThrows<IllegalArgumentException> { Sha256("a".repeat(63)) }
        assertThrows<IllegalArgumentException> { Sha256("a".repeat(65)) }
        assertThrows<IllegalArgumentException> { Sha256("g".repeat(64)) }
        assertThrows<IllegalArgumentException> { Sha256("F".repeat(64)) }
        assertThrows<IllegalArgumentException> { Sha256.fromHex("a".repeat(63)) }
        assertThrows<IllegalArgumentException> { Sha256.fromHex("a b".padEnd(64, 'a')) }
    }

    @Test
    fun toolNameAcceptsDocumentedShapes() {
        val names =
            listOf(
                "read",
                "write",
                "bash",
                "time.now",
                "files.list",
                "browser.click",
                "code.javascript.run",
                "mcp.my_server.tool-a",
            )
        for (name in names) {
            assertEquals(name, ToolName(name).value)
            assertEquals(name, ToolName(name).toString())
        }
    }

    @Test
    fun toolNameRejectsInvalidShapes() {
        for (name in listOf("", ".", "a..b", ".a", "a.", "a b", "a/b", "a" + "-".repeat(65))) {
            assertThrows<IllegalArgumentException>("tool name $name accepted") { ToolName(name) }
        }
        // Over total length, over segment count, over segment length, or bad first character.
        assertThrows<IllegalArgumentException> { ToolName("a".repeat(129)) }
        assertThrows<IllegalArgumentException> { ToolName(List(9) { "a" }.joinToString(".")) }
        assertThrows<IllegalArgumentException> { ToolName("a." + "b".repeat(65)) }
        assertThrows<IllegalArgumentException> { ToolName("-a") }
        // Strict ASCII: Unicode letters/digits are not in the [A-Za-z0-9_-] contract.
        assertThrows<IllegalArgumentException> { ToolName("café") }
        assertThrows<IllegalArgumentException> { ToolName("café.tool") }
        assertThrows<IllegalArgumentException> { ToolName("１２３") }
    }

    @Test
    fun toolVersionRequiresNonNegative() {
        assertEquals(0, ToolVersion(0).value)
        assertEquals(3, ToolVersion(3).value)
        assertEquals("7", ToolVersion(7).toString())
        assertThrows<IllegalArgumentException> { ToolVersion(-1) }
    }

    @Test
    fun artifactRefAcceptsOpaqueTokens() {
        for (ref in listOf("a", "art-1_2.3:4", "x".repeat(128))) {
            assertEquals(ref, ArtifactRef(ref).value)
            assertEquals(ref, ArtifactRef(ref).toString())
        }
    }

    @Test
    fun artifactRefRejectsPathLikeOrExoticTokens() {
        for (ref in listOf("", "a".repeat(129), "a/b", "a" + '\\' + "b", "a b", "café", "a#b", "a\nb")) {
            assertThrows<IllegalArgumentException>("artifact ref $ref accepted") { ArtifactRef(ref) }
        }
    }
}
