package com.helix.app.proot

import com.helix.app.tool.SessionToolEffectClassifier
import com.helix.core.model.OperationEffect
import com.helix.core.model.OperationRule
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolAvailabilityState
import com.helix.core.model.ToolAvailabilityStates
import com.helix.core.policy.SessionPermissionConfig
import com.helix.core.policy.SessionPermissionSource
import com.helix.core.policy.ToolAvailabilitySource
import com.helix.runtime.proot.ipc.ProotJobCommand
import com.helix.runtime.proot.ipc.ProotJobSpec
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HXA-209 C5 (ADR-PERMISSIONS-001 section 4): the not-yet-launched recheck for the one real
 * OS background execution (PRoot). The recheck runs LIVE at submit time through the SAME
 * seams, classifier and resolver as the dispatch start gate:
 *
 * - a tool DISABLE or a resolver DENY is a NEW PROHIBITION the held proof never covered, so
 *   the launch is refused side-effect-free (nothing submitted, no journal or binding row);
 * - AUTO_PROCEED is a plain pass;
 * - a tightening to REQUIRES_APPROVAL (including the rm floor) still PROCEEDS: the call
 *   already spent the exact per-call proof for precisely this command (section 4: a valid
 *   exact proof is reusable while it hits no new prohibition).
 *
 * The REAL classifier and the REAL descriptor run here; only the two policy seams are faked
 * (they are plain fun interfaces). The binding payload is asserted through the companion —
 * the v2 row binds the job to its session and to the config version that covered its
 * approval (sections 4 + 5), with present-but-null keys when no config exists.
 */
@Suppress("TooManyFunctions") // 8 test methods + 4 shared fixtures (recheck/linuxCall/payload/codeOf)
class LinuxSessionPermissionRecheckTest {
    @Test
    fun aToolDisabledAfterApprovalRefusesTheLaunch() {
        // A disable (any scope) after the approval is a new prohibition the held proof never
        // covered — even if the session mode itself would still ALLOW the command.
        val recheck =
            recheck(
                config = SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                global = ToolAvailabilityState.DISABLED,
            )
        val result = recheck.check(linuxCall())
        val failed = requireNotNull(result as? ToolExecutorResult.Failed) { "expected a Failed result, got $result" }
        assertEquals("SESSION_TOOL_DISABLED", codeOf(failed))
        assertTrue("the refusal must be side-effect-free", failed.sideEffectFree)
    }

    @Test
    fun aDenyRuleTightenedAfterApprovalRefusesTheLaunch() {
        val recheck =
            recheck(
                config =
                    SessionPermissionConfig.custom(
                        mapOf(OperationEffect.COMMAND_EXECUTION to OperationRule.DENY),
                    ),
            )
        val result = recheck.check(linuxCall())
        val failed = requireNotNull(result as? ToolExecutorResult.Failed) { "expected a Failed result, got $result" }
        assertEquals("SESSION_OPERATION_DENIED", codeOf(failed))
    }

    @Test
    fun aTighteningToAskStillProceedsWithTheHeldProof() {
        // WORKSPACE mode asks for COMMAND_EXECUTION, but this exact call already consumed the
        // precise one-time approval for precisely this command — the ASK floor was met at
        // approval time, so the recheck proceeds.
        val recheck = recheck(config = SessionPermissionConfig.of(SessionPermissionMode.WORKSPACE))
        assertNull(recheck.check(linuxCall()))
    }

    @Test
    fun anAutoProceedingModeStillProceeds() {
        val recheck = recheck(config = SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS))
        assertNull(recheck.check(linuxCall()))
    }

    @Test
    fun theRmFloorStillProceedsWithTheHeldProof() {
        // The explicit `rm -rf` rule forces a precise one-time approval in EVERY mode — even
        // FULL_ACCESS. That approval was already spent for this exact call, so the floor does
        // not re-refuse at launch; only a disable or DENY would.
        val recheck = recheck(config = SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS))
        assertNull(recheck.check(linuxCall(command = ProotJobCommand.Script("rm -rf /tmp/x"))))
    }

    @Test
    fun aMissingSessionContextRefusesTheLaunch() {
        // No trusted session id means the authorization cannot be rechecked at all — the job
        // must not be launched rather than silently skipped.
        val recheck = recheck(config = SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS))
        val result = recheck.check(linuxCall(sessionId = null))
        val failed = requireNotNull(result as? ToolExecutorResult.Failed) { "expected a Failed result, got $result" }
        assertEquals("SESSION_CONTEXT_MISSING", codeOf(failed))
    }

    @Test
    fun theBindingPayloadBindsTheJobToItsSessionAndConfigVersion() {
        val payload = payload(SessionPermissionConfig.of(SessionPermissionMode.WORKSPACE))
        assertEquals("2", payload.getValue("version").jsonPrimitive.content)
        assertEquals("call-1", payload.getValue("toolCallId").jsonPrimitive.content)
        assertEquals("turn-1", payload.getValue("turnId").jsonPrimitive.content)
        assertEquals("s1", payload.getValue("sessionId").jsonPrimitive.content)
        assertEquals("WORKSPACE", payload.getValue("mode").jsonPrimitive.content)
        assertEquals("1", payload.getValue("configVersion").jsonPrimitive.content)
        assertEquals("job_0123456789ab", payload.getValue("jobId").jsonPrimitive.content)
        assertEquals("exec-1", payload.getValue("executionId").jsonPrimitive.content)
        assertEquals("a".repeat(64), payload.getValue("inputManifestSha256").jsonPrimitive.content)
        // Primitives only — no command, environment or path leaves the payload.
        assertEquals(
            setOf(
                "version",
                "toolCallId",
                "turnId",
                "sessionId",
                "mode",
                "configVersion",
                "jobId",
                "executionId",
                "inputManifestSha256",
            ),
            payload.keys,
        )
    }

    @Test
    fun anAbsentConfigKeepsThePayloadShapeWithNullBinding() {
        // Recovery paths (and an unwired seam) write through a null config: the keys stay
        // present but null, so readers of either row shape never see a missing field.
        val payload = payload(null)
        assertTrue(payload.getValue("mode") is JsonNull)
        assertTrue(payload.getValue("configVersion") is JsonNull)
        assertEquals("2", payload.getValue("version").jsonPrimitive.content)
        assertEquals("s1", payload.getValue("sessionId").jsonPrimitive.content)
    }

    private fun payload(config: SessionPermissionConfig?): JsonObject =
        ProotJobBindingStore.jobPreparedPayload(
            toolCallId = "call-1",
            turnId = "turn-1",
            sessionId = "s1",
            spec =
                ProotJobSpec(
                    executionId = "exec-1",
                    jobId = "job_0123456789ab",
                    command = ProotJobCommand.Argv(listOf("ls")),
                    relativeWorkingDirectory = "",
                    environment = emptyMap(),
                    deadlineMs = 60_000L,
                    maxOutputBytes = 1_024L,
                    inputManifestSha256 = "a".repeat(64),
                ),
            config = config,
        )

    private fun recheck(
        config: SessionPermissionConfig,
        global: ToolAvailabilityState? = null,
    ): LinuxSessionPermissionRecheck =
        LinuxSessionPermissionRecheck(
            permissions = SessionPermissionSource { config },
            availability = ToolAvailabilitySource { _, _, _, _ -> ToolAvailabilityStates(global = global) },
            classifier = SessionToolEffectClassifier { "ws-1" },
            descriptor = LinuxRunTool.descriptor(),
        )

    private fun linuxCall(
        sessionId: String? = "s1",
        command: ProotJobCommand = ProotJobCommand.Argv(listOf("ls")),
    ): LinuxRunTool.ParsedLinuxCall =
        LinuxRunTool.ParsedLinuxCall(
            toolCallId = "call-1",
            turnId = "turn-1",
            sessionId = sessionId,
            command = command,
            cwd = "",
            environment = emptyMap(),
            inputReferences = emptyList(),
            outputReference = null,
            deadlineEpochMs = 120_000L,
        )

    private fun codeOf(failed: ToolExecutorResult.Failed): String =
        failed
            .auditDetail
            ?.let { it["code"]?.jsonPrimitive?.content }
            ?: error("expected a Failed result")
}
