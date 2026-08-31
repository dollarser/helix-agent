package com.helix.core.agent

import com.helix.core.model.ArtifactRef
import com.helix.core.model.SessionId
import com.helix.core.model.Sha256
import com.helix.core.model.TurnId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * HXA-016 context builder (architecture doc 5.4, security doc 7): auditable items,
 * deterministic trim, bounded summaries, no character-level truncation, conservative token
 * accounting. Roadmap test focus: untrusted sources, over-budget, stable ordering, multiple
 * tool results, missing token usage.
 */
class ContextBuilderTest {
    private val capability = ProviderCapability("provider-1", "model-1", 100_000)
    private val session = SessionId("session-1")
    private val turn = TurnId("turn-1")

    private fun request(
        budget: Long,
        vararg sources: ContextSource,
    ) = ContextBuildRequest(session, turn, capability, budget, sources.toList())

    private fun source(
        type: ContextSourceType,
        id: String,
        content: String,
        trust: ContextTrust = ContextTrust.TRUSTED,
        hash: Sha256? = null,
        ref: ArtifactRef? = null,
        retained: Boolean = false,
    ) = ContextSource(type, id, trust, content, hash, ref, retained)

    private fun build(
        budget: Long,
        vararg sources: ContextSource,
    ): ContextBuildResult = ContextBuilder.build(request(budget, *sources))

    // ---------------------------------------------------------------- basic assembly

    @Test
    fun `a single user message builds one auditable item`() {
        val result = build(100, source(ContextSourceType.USER, "u1", "hello"))
        assertEquals(1, result.items.size)
        val item = result.items.single()
        assertEquals(ContextSourceType.USER, item.sourceType)
        assertEquals("u1", item.sourceId)
        assertEquals(ContextTrust.TRUSTED, item.trust)
        assertEquals("hello", item.content)
        assertNull(item.contentRef)
        // 5 bytes -> ceil(5/4) = 2 tokens; hash of the exact content.
        assertEquals(2, item.estimatedTokens)
        assertEquals(
            Sha256.fromHex("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"),
            item.contentHash,
        )
        assertEquals(2, result.totalEstimatedTokens)
        assertFalse(result.trimmed)
    }

    @Test
    fun `an empty snapshot builds an empty context`() {
        val result = build(100)
        assertTrue(result.items.isEmpty())
        assertEquals(0, result.totalEstimatedTokens)
        assertTrue(result.droppedSourceIds.isEmpty())
    }

    @Test
    fun `contracts come first and are never dropped`() {
        // Contracts reserve space (8 tokens) and must lead the request; with the remaining
        // 45 tokens only the newest 100-byte message (25) fits, the old one is trimmed.
        val old = source(ContextSourceType.USER, "u-old", "x".repeat(100))
        val system = source(ContextSourceType.SYSTEM, "sys", "system contract")
        val policy = source(ContextSourceType.MODE_POLICY, "pol", "mode contract")
        val fresh = source(ContextSourceType.USER, "u-new", "y".repeat(100))
        val result = build(53, old, system, policy, fresh)
        assertEquals(listOf("sys", "pol", "u-new"), result.items.map { it.sourceId })
        assertEquals(listOf("u-old"), result.droppedSourceIds)
        assertTrue(result.trimmed)
    }

    // ---------------------------------------------------------------- trust

    @Test
    fun `untrusted content sources must be marked untrusted`() {
        for (type in ContextSourceType.UNTRUSTED_SOURCES) {
            assertThrows<IllegalArgumentException>("$type must be UNTRUSTED") {
                source(type, "s1", "content", trust = ContextTrust.TRUSTED)
            }
        }
    }

    @Test
    fun `untrusted sources pass through with their trust marking`() {
        val result =
            build(
                1000,
                source(ContextSourceType.WEB, "w1", "page text", trust = ContextTrust.UNTRUSTED),
                source(ContextSourceType.FILE, "f1", "file text", trust = ContextTrust.UNTRUSTED),
                source(ContextSourceType.MCP, "m1", "mcp text", trust = ContextTrust.UNTRUSTED),
                source(ContextSourceType.SKILL, "k1", "skill text", trust = ContextTrust.UNTRUSTED),
                source(ContextSourceType.NOTIFICATION, "n1", "notif text", trust = ContextTrust.UNTRUSTED),
                source(ContextSourceType.ACCESSIBILITY, "a1", "node text", trust = ContextTrust.UNTRUSTED),
            )
        assertEquals(6, result.items.size)
        for (item in result.items) {
            assertEquals(ContextTrust.UNTRUSTED, item.trust)
        }
        // Snapshot order is preserved after the (empty) contract prefix.
        assertEquals(listOf("w1", "f1", "m1", "k1", "n1", "a1"), result.items.map { it.sourceId })
    }

    @Test
    fun `contracts must be trusted`() {
        assertThrows<IllegalArgumentException>("SYSTEM must be TRUSTED") {
            source(ContextSourceType.SYSTEM, "sys", "content", trust = ContextTrust.UNTRUSTED)
        }
        assertThrows<IllegalArgumentException>("MODE_POLICY must be TRUSTED") {
            source(ContextSourceType.MODE_POLICY, "pol", "content", trust = ContextTrust.UNTRUSTED)
        }
    }

    // ---------------------------------------------------------------- deterministic trim

    @Test
    fun `over budget drops the oldest non-retained items first`() {
        val messages = (1..5).map { source(ContextSourceType.USER, "u$it", "x".repeat(100)) }
        val result = build(60, *messages.toTypedArray())
        // 25 tokens each; newest first: u5 + u4 fit (50 <= 60), u3 does not.
        assertEquals(listOf("u4", "u5"), result.items.map { it.sourceId })
        assertEquals(listOf("u1", "u2", "u3"), result.droppedSourceIds)
        assertEquals(50, result.totalEstimatedTokens)
    }

    @Test
    fun `retained items are never dropped and keep their position`() {
        val oldResult = source(ContextSourceType.TOOL_RESULT, "r1", "z".repeat(100), retained = true)
        val mid = source(ContextSourceType.ASSISTANT, "a1", "m".repeat(100))
        val fresh = source(ContextSourceType.USER, "u1", "n".repeat(100))
        // Retained r1 reserves 25 tokens; of the remaining 25 only the newest fits.
        val result = build(50, oldResult, mid, fresh)
        assertEquals(listOf("r1", "u1"), result.items.map { it.sourceId })
        assertEquals(listOf("a1"), result.droppedSourceIds)
    }

    @Test
    fun `retained context alone over budget fails closed`() {
        val big = source(ContextSourceType.TOOL_CALL, "tc1", "q".repeat(500), retained = true)
        val error = assertThrows<IllegalStateException>("retained over budget") { build(100, big) }
        assertTrue(error.message!!.contains("exceeds"))
    }

    @Test
    fun `the build is deterministic and stable across runs`() {
        // u1..u8 = 51..58 bytes = 13,13,14,14,14,14,15,15 tokens (total 112).
        val sources =
            (1..8)
                .map {
                    source(ContextSourceType.USER, "u$it", "x".repeat(50 + it))
                }.toTypedArray()
        val first = ContextBuilder.build(request(150, *sources))
        assertEquals(first, ContextBuilder.build(request(150, *sources)))
        assertEquals(8, first.items.size)

        // A tighter budget forces a stable cut: newest first u8(15)+u7(15) = 30 <= 40,
        // u6(14) would exceed.
        val cut = ContextBuilder.build(request(40, *sources))
        assertEquals(cut, ContextBuilder.build(request(40, *sources)))
        assertEquals(listOf("u7", "u8"), cut.items.map { it.sourceId })
        assertEquals(listOf("u1", "u2", "u3", "u4", "u5", "u6"), cut.droppedSourceIds)
        assertEquals(30, cut.totalEstimatedTokens)
    }

    @Test
    fun `equal-sized items drop by snapshot position, newest kept`() {
        // Five identical 4-byte (1 token) items, budget 3: the three newest survive in order.
        val sources = (1..5).map { source(ContextSourceType.ASSISTANT, "a$it", "wxyz") }.toTypedArray()
        val result = ContextBuilder.build(request(3, *sources))
        assertEquals(listOf("a3", "a4", "a5"), result.items.map { it.sourceId })
        assertEquals(listOf("a1", "a2"), result.droppedSourceIds)
    }

    // ---------------------------------------------------------------- tool results

    @Test
    fun `multiple tool results stay intact and are auditable`() {
        val fullJson = """{"status":"ok","lines":42,"path":"/tmp/a.txt"}"""
        val bigSummary = "File /tmp/big.log (1.2 MiB): first matching lines ..."
        val ref = ArtifactRef("art.abcd1234")
        val fullHash = Sha256.fromHex("0".repeat(64))
        val retainedResult = source(ContextSourceType.TOOL_RESULT, "r1", fullJson, retained = true)
        val summarized =
            source(ContextSourceType.TOOL_RESULT, "r2", bigSummary, hash = fullHash, ref = ref)
        val third = source(ContextSourceType.TOOL_RESULT, "r3", "denied: policy L3")
        val result = build(2000, retainedResult, summarized, third)
        assertEquals(listOf("r1", "r2", "r3"), result.items.map { it.sourceId })
        // Byte-identical content — no character-level truncation anywhere.
        assertEquals(fullJson, result.items[0].content)
        assertEquals(bigSummary, result.items[1].content)
        assertEquals("denied: policy L3", result.items[2].content)
        // The summary item carries the full-content binding; full items get computed hashes.
        assertEquals(ref, result.items[1].contentRef)
        assertEquals(fullHash, result.items[1].contentHash)
        assertNull(result.items[0].contentRef)
        assertEquals(
            Sha256(
                MessageDigest.getInstance("SHA-256").digest(fullJson.toByteArray(Charsets.UTF_8)).joinToString("") {
                    "%02x".format(it)
                },
            ),
            result.items[0].contentHash,
        )
    }

    @Test
    fun `a retained tool call carries its full canonical arguments verbatim`() {
        val head = """{"cmd":"find . -name '*.kt'","timeout_ms":5000,"cwd":"/workspace"}"""
        val argsJson = head + "\"pad\":\"${"p".repeat(8_000)}\""
        val call = source(ContextSourceType.TOOL_CALL, "tc1", argsJson, retained = true)
        val result = build(10_000, call)
        assertEquals(argsJson, result.items.single().content)
        assertEquals(1, result.items.size)
    }

    @Test
    fun `a summary replacement requires the full content hash`() {
        assertThrows<IllegalArgumentException>("ref without hash") {
            source(
                ContextSourceType.TOOL_RESULT,
                "r1",
                "bounded summary",
                ref = ArtifactRef("art.abcd1234"),
            )
        }
    }

    @Test
    fun `an oversized full content is rejected without truncation`() {
        val error =
            assertThrows<IllegalArgumentException>("no silent truncation") {
                build(100_000, source(ContextSourceType.USER, "u1", "x".repeat(32_769)))
            }
        assertTrue(error.message!!.contains("no character-level truncation"))
    }

    @Test
    fun `an oversized summary is rejected without truncation`() {
        val error =
            assertThrows<IllegalArgumentException>("summary bound") {
                build(
                    100_000,
                    source(
                        ContextSourceType.TOOL_RESULT,
                        "r1",
                        "s".repeat(2_049),
                        hash = Sha256.fromHex("0".repeat(64)),
                        ref = ArtifactRef("art.abcd1234"),
                    ),
                )
            }
        assertTrue(error.message!!.contains("no character-level truncation"))
    }

    @Test
    fun `a provided hash must match the content`() {
        assertThrows<IllegalArgumentException>("hash mismatch") {
            build(1000, source(ContextSourceType.USER, "u1", "hello", hash = Sha256.fromHex("0".repeat(64))))
        }
    }

    // ---------------------------------------------------------------- token accounting

    @Test
    fun `missing token usage falls back to the conservative estimate and never zero`() {
        // 1 byte -> 1 token (never 0); 33 bytes -> ceil(33/4) = 9; 4 bytes -> exactly 1.
        val one = build(10, source(ContextSourceType.USER, "u1", "x"))
        assertEquals(1, one.items.single().estimatedTokens)
        val thirtyThree = build(100, source(ContextSourceType.USER, "u1", "x".repeat(33)))
        assertEquals(9, thirtyThree.items.single().estimatedTokens)
        val four = build(10, source(ContextSourceType.USER, "u1", "abcd"))
        assertEquals(1, four.items.single().estimatedTokens)
        // The total is exactly the sum of the per-item estimates.
        val mixed =
            build(
                1000,
                source(ContextSourceType.USER, "u1", "x".repeat(10)),
                source(ContextSourceType.USER, "u2", "y".repeat(6)),
            )
        assertEquals(mixed.items.sumOf { it.estimatedTokens }, mixed.totalEstimatedTokens)
    }

    // ---------------------------------------------------------------- request validation

    @Test
    fun `duplicate source ids are rejected`() {
        assertThrows<IllegalArgumentException>("unique ids") {
            request(100, source(ContextSourceType.USER, "u1", "a"), source(ContextSourceType.USER, "u1", "b"))
        }
    }

    @Test
    fun `the input budget must be the stricter of config and capability`() {
        assertThrows<IllegalArgumentException>("budget within capability") {
            request(100_001, source(ContextSourceType.USER, "u1", "a"))
        }
        // Equal to the capability is allowed (the strictest possible choice).
        val result = request(100_000, source(ContextSourceType.USER, "u1", "a"))
        ContextBuilder.build(result)
    }

    @Test
    fun `blank content and blank ids are rejected`() {
        assertThrows<IllegalArgumentException>("blank content") {
            source(ContextSourceType.USER, "u1", "   ")
        }
        assertThrows<IllegalArgumentException>("blank id") {
            source(ContextSourceType.USER, "", "a")
        }
    }
}
