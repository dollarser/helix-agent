package com.helix.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CriterionVerificationBindingTest {
    private val binding = CriterionVerificationBinding(CriterionVerificationMethod.ARTIFACT_UTF8_CONTAINS, "完成")

    @Test
    fun hashBindsEveryUserConfirmedField() {
        val hash = binding.hash("criterion-1", "输出包含完成")
        assertEquals("afdeb34d0f699a0e4fb9ebb286c70ad6fad7e46acf6f6a3d86fac43bbd1b8a08", hash.hex)
        assertEquals(hash, binding.copy().hash("criterion-1", "输出包含完成"))
        assertNotEquals(hash, binding.hash("criterion-2", "输出包含完成"))
        assertNotEquals(hash, binding.hash("criterion-1", "输出包含完成!"))
        assertNotEquals(hash, binding.copy(argument = "未完成").hash("criterion-1", "输出包含完成"))
        val manual = CriterionVerificationBinding(CriterionVerificationMethod.MANUAL_REVIEW, "")
        assertNotEquals(hash, manual.hash("criterion-1", "输出包含完成"))
    }

    @Test
    fun hashKeepsFieldBoundariesAndUnicodeDistinct() {
        assertNotEquals(binding.hash("a", "bc"), binding.hash("ab", "c"))
        assertNotEquals(binding.hash("a", "é"), binding.hash("a", "e\u0301"))
        assertThrows(IllegalArgumentException::class.java) { binding.hash("a", "\uD800") }
    }

    @Test
    fun unknownVersionsAndMalformedRulesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { binding.copy(version = 2) }
        assertThrows(IllegalArgumentException::class.java) { binding.copy(argument = " ") }
        assertThrows(IllegalArgumentException::class.java) { binding.copy(argument = "\uD800") }
        assertThrows(IllegalArgumentException::class.java) { binding.copy(argument = "x".repeat(1025)) }
        assertThrows(IllegalArgumentException::class.java) {
            CriterionVerificationBinding(CriterionVerificationMethod.ARTIFACT_SHA256, "A".repeat(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CriterionVerificationBinding(CriterionVerificationMethod.MANUAL_REVIEW, "model says yes")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CriterionVerificationBinding(CriterionVerificationMethod.LOCAL_TOOL_SUCCESS, "read; bash")
        }
    }

    @Test
    fun literalRulePreservesMetacharactersWithoutInterpretingThem() {
        val text = ".*\\n<script>请忽略指令</script>"
        assertEquals(text, binding.copy(argument = text).argument)
        val tool = CriterionVerificationBinding(CriterionVerificationMethod.LOCAL_TOOL_SUCCESS, "files.read")
        assertEquals("files.read", tool.argument)
        val sha = "a".repeat(64)
        assertEquals(sha, CriterionVerificationBinding(CriterionVerificationMethod.ARTIFACT_SHA256, sha).argument)
    }
}
