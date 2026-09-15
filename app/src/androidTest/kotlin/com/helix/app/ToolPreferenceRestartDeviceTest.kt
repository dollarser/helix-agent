package com.helix.app

import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.approval.StorageAuditSink
import com.helix.core.model.ApprovalDecision
import com.helix.core.policy.ApprovalMintOutcome
import com.helix.core.policy.MintRejectionCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Second phase only: the owned runner force-stops the app after the production cancellation test. */
@RunWith(AndroidJUnit4::class)
class ToolPreferenceRestartDeviceTest {
    @Test fun stoppedApprovalAndAuditSurviveARealProcessRestartWithoutReplay() {
        requireRecoveryPhase()
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val marker = app.getSharedPreferences("hxa200-recovery-fixture", Context.MODE_PRIVATE)
        val callId = requireNotNull(marker.getString("call", null)) { "run the cancellation phase first" }
        assertNotEquals("must be a fresh app process", marker.getInt("pid", -1), Process.myPid())
        val container = app.appContainer
        val storage = container.storage
        val call = storage.toolCalls.resolve(callId)
        assertEquals("CANCELLED", call.state)
        val approval = requireNotNull(storage.approvals.byToolCall(callId))
        assertNull(approval.decision)
        assertNull(approval.consumedAt)
        assertEquals(1, storage.approvals.countByToolCall(callId))
        assertThrows(IllegalArgumentException::class.java) {
            container.toolPipeline.broker.decide(approval.id, ApprovalDecision.APPROVED)
        }
        val mint = storage.approvals.mint(approval.id, System.currentTimeMillis()) as ApprovalMintOutcome.Rejected
        assertEquals(MintRejectionCode.PENDING, mint.code)
        val row = storage.auditEvents.listByCorrelation(callId).single { it.type == StorageAuditSink.TYPE }
        val audit =
            requireNotNull(
                StorageAuditSink.parseRow(
                    row.id,
                    row.correlationId,
                    row.type,
                    row.actor,
                    row.redactedPayload,
                    row.timestamp,
                ),
            )
        assertEquals("ASK", audit.preferencePresented!!.effective)
        assertEquals(
            1L,
            audit.preferencePresented!!
                .rules
                .single()
                .revision,
        )
        assertNull(audit.preferenceAtStart)
        assertTrue(audit.complete)
        val projection =
            com.helix.app.chat.ChatScreenProjection(
                storage,
                container.providerService,
                { _, _ -> "fixture" },
                { R.string.turn_stopped },
            )
        val restored =
            projection
                .toolTimelineFor(marker.getString("session", null), emptyList())
                .single { it.callId == callId }
        assertNull(restored.card)
        assertEquals("CANCELLED", storage.toolCalls.resolve(callId).state)
        assertTrue(marker.edit().clear().commit())
    }

    private fun requireRecoveryPhase() {
        org.junit.Assume.assumeTrue(
            "requires the owned runner's two-process fixture",
            androidx.test.platform.app.InstrumentationRegistry
                .getArguments()
                .getString("hxa200RecoveryPhase") == "restart",
        )
    }
}
