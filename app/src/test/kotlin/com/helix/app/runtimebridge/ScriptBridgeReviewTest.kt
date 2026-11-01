package com.helix.app.runtimebridge

import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptBridgeReviewTest {
    @Test fun claimedScriptSuccessCannotEraseAnUnsettledChildOrItsAuditIdentity() {
        val success =
            ToolExecutorResult.Completed(
                buildJsonObject { put("claimed", "done") },
                buildJsonObject { put("sourceSha256", "synthetic-hash") },
            )
        assertSame(success, reviewUnresolvedScript(success, emptySet()))
        val result = reviewUnresolvedScript(success, setOf("original-child")) as ToolExecutorResult.Failed
        assertTrue(result.requiresReview)
        assertFalse(result.sideEffectFree)
        assertTrue(result.detail.contains("original-child"))
        assertTrue(result.detail.contains("claimed"))
        assertEquals(success.auditDetail?.get("sourceSha256"), result.auditDetail?.get("sourceSha256"))
    }
}
