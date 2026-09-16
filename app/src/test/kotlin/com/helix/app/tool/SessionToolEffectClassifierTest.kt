package com.helix.app.tool

import com.helix.core.model.AgentMode
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.OperationEffect
import com.helix.core.model.RiskLevel
import com.helix.core.model.SafetyProfile
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.core.policy.DataOrigin
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolDispatchRequest
import com.helix.tools.framework.ToolOrigin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * HXA-209 B3 (ADR-PERMISSIONS-001 section 2 step 4, execution-domain matrix section 5): the
 * platform-side effect classification. Fail closed throughout — an unproven effect is
 * UNDETERMINED, an unparseable scope reference is EXTERNAL, and a remote tool name never proves
 * the absence of a remote write.
 */
class SessionToolEffectClassifierTest {
    private val bound = SessionToolEffectClassifier { "ws-1" }
    private val unbound = SessionToolEffectClassifier { null }

    /** Everything the Shell lane cannot rule out on this device (it runs with full reach). */
    private val linuxUndetermined =
        setOf(
            OperationEffect.FILE_READ_WORKSPACE,
            OperationEffect.FILE_READ_EXTERNAL,
            OperationEffect.FILE_MUTATION_WORKSPACE,
            OperationEffect.FILE_MUTATION_EXTERNAL,
            OperationEffect.REMOTE_BUSINESS_MUTATION,
            OperationEffect.DEVICE_SYSTEM_MUTATION,
        )

    @Test
    fun theShellLaneDeterminesOnlyCommandExecutionAndFlagsRmRf() {
        val argv =
            bound.classify(
                request("""{"argv":["rm","-rf","/tmp/x"]}"""),
                descriptor("code.linux.run", ToolOperationClass.CODE_EXECUTION),
            )
        assertEquals(setOf(OperationEffect.COMMAND_EXECUTION), argv.footprint.effects)
        assertEquals(linuxUndetermined, argv.footprint.undeterminedEffects)
        assertTrue(argv.rmCommandHit)

        val script =
            bound.classify(
                request("""{"script":"rm -rf /var/x"}"""),
                descriptor("code.linux.run", ToolOperationClass.CODE_EXECUTION),
            )
        assertTrue("the floor rule reads the script lane too", script.rmCommandHit)

        val plain =
            bound.classify(
                request("""{"argv":["ls","-la"]}"""),
                descriptor("code.linux.run", ToolOperationClass.CODE_EXECUTION),
            )
        assertFalse(plain.rmCommandHit)
        assertEquals(setOf(OperationEffect.COMMAND_EXECUTION), plain.footprint.effects)
        assertEquals(linuxUndetermined, plain.footprint.undeterminedEffects)
    }

    @Test
    fun quickJsIsSandboxedCommandExecutionOnly() {
        val c =
            bound.classify(request(), descriptor("code.javascript.run", ToolOperationClass.CODE_EXECUTION))
        assertEquals(setOf(OperationEffect.COMMAND_EXECUTION), c.footprint.effects)
        assertTrue(c.footprint.undeterminedEffects.isEmpty())
        assertFalse(c.rmCommandHit)
    }

    @Test
    fun remoteOriginsAreUndeterminedRemoteBusinessMutation() {
        val mcp =
            descriptor(
                "mcp.catalog.tool_1",
                ToolOperationClass.NETWORK,
                origin = ToolOrigin.McpOrigin("catalog", "2025-03-26", "a".repeat(64)),
            )
        val a2a =
            descriptor(
                "a2a.echo_skill",
                ToolOperationClass.NETWORK,
                origin =
                    ToolOrigin.A2aOrigin(
                        "agent-1",
                        "echo-skill",
                        "https://agent.example:443",
                        "JSONRPC",
                        "1.0",
                        "c".repeat(64),
                        "d".repeat(64),
                    ),
            )
        for (remote in listOf(mcp, a2a)) {
            val c = bound.classify(request(), remote)
            assertEquals(emptySet<OperationEffect>(), c.footprint.effects)
            assertEquals(setOf(OperationEffect.REMOTE_BUSINESS_MUTATION), c.footprint.undeterminedEffects)
        }
    }

    @Test
    fun fileReadsResolveAgainstTheBoundWorkspaceScope() {
        val inside = bound.classify(request("""{"path":"scope:ws-1:notes.md"}"""), descriptor("read"))
        assertEquals(setOf(OperationEffect.FILE_READ_WORKSPACE), inside.footprint.effects)
        assertTrue(inside.footprint.undeterminedEffects.isEmpty())

        val outside = bound.classify(request("""{"path":"scope:other-9:notes.md"}"""), descriptor("read"))
        assertEquals(setOf(OperationEffect.FILE_READ_EXTERNAL), outside.footprint.effects)
    }

    @Test
    fun unparseableScopeReferencesFailClosedToExternal() {
        for (reference in listOf("scope:!!!:x", "scope:noclose", "scope:ws-1")) {
            val c = bound.classify(request("""{"path":"$reference"}"""), descriptor("read"))
            assertEquals(setOf(OperationEffect.FILE_READ_EXTERNAL), c.footprint.effects)
        }
    }

    @Test
    fun aNullWorkspaceBindsEveryReferenceExternal() {
        val c = unbound.classify(request("""{"path":"scope:ws-1:notes.md"}"""), descriptor("read"))
        assertEquals(setOf(OperationEffect.FILE_READ_EXTERNAL), c.footprint.effects)
    }

    @Test
    fun fileMutationTargetsResolveAgainstTheBoundWorkspaceScope() {
        val inside = bound.classify(request("""{"path":"scope:ws-1:new.md"}"""), descriptor("write"))
        assertEquals(setOf(OperationEffect.FILE_MUTATION_WORKSPACE), inside.footprint.effects)

        val outside = bound.classify(request("""{"path":"scope:other-9:new.md"}"""), descriptor("write"))
        assertEquals(setOf(OperationEffect.FILE_MUTATION_EXTERNAL), outside.footprint.effects)
    }

    @Test
    fun copyReadsItsSourceAndMutatesItsDestination() {
        val c =
            bound.classify(
                request("""{"source":"scope:ws-1:a.md","destination":"scope:other-9:b.md"}"""),
                descriptor("files.copy"),
            )
        assertEquals(
            setOf(OperationEffect.FILE_READ_WORKSPACE, OperationEffect.FILE_MUTATION_EXTERNAL),
            c.footprint.effects,
        )
    }

    @Test
    fun moveAlsoMutatesItsSource() {
        val c =
            bound.classify(
                request("""{"source":"scope:ws-1:a.md","destination":"scope:other-9:b.md"}"""),
                descriptor("files.move"),
            )
        assertEquals(
            setOf(
                OperationEffect.FILE_READ_WORKSPACE,
                OperationEffect.FILE_MUTATION_WORKSPACE,
                OperationEffect.FILE_MUTATION_EXTERNAL,
            ),
            c.footprint.effects,
        )
    }

    @Test
    fun anUnrecognizedScopeReferencingArgumentOnAMutationToolIsAMutation() {
        val c =
            bound.classify(
                request("""{"archive":"scope:ws-1:a.zip","path":"scope:ws-1:out"}"""),
                descriptor("files.extract"),
            )
        assertEquals(setOf(OperationEffect.FILE_MUTATION_WORKSPACE), c.footprint.effects)
    }

    @Test
    fun readToolsWithoutScopeReferencesAreClean() {
        val c = bound.classify(request("""{"limit":10}"""), descriptor("time.now"))
        assertEquals(emptySet<OperationEffect>(), c.footprint.effects)
        assertTrue(c.footprint.undeterminedEffects.isEmpty())
    }

    @Test
    fun browserInteractionIsUndeterminedRemoteBusinessMutation() {
        val c = bound.classify(request(), descriptor("browser.click", ToolOperationClass.EXTERNAL_ACTION))
        assertEquals(setOf(OperationEffect.REMOTE_BUSINESS_MUTATION), c.footprint.undeterminedEffects)
        assertTrue(c.footprint.effects.isEmpty())
    }

    @Test
    fun httpFetchIsACleanNetworkRead() {
        val c = bound.classify(request(), descriptor("http.fetch", ToolOperationClass.NETWORK))
        assertEquals(emptySet<OperationEffect>(), c.footprint.effects)
        assertTrue(c.footprint.undeterminedEffects.isEmpty())
    }

    @Test
    fun otherNetworkToolsAreUndeterminedRemoteBusinessMutation() {
        val c = bound.classify(request(), descriptor("web.scrape", ToolOperationClass.NETWORK))
        assertEquals(setOf(OperationEffect.REMOTE_BUSINESS_MUTATION), c.footprint.undeterminedEffects)
    }

    @Test
    fun privilegedAndUnknownToolsAreUndeterminedDeviceSystemMutation() {
        for (name in listOf("android.clipboard.write", "device.screenshot")) {
            val c = bound.classify(request(), descriptor(name, ToolOperationClass.EXTERNAL_ACTION, RiskLevel.L1))
            assertEquals(setOf(OperationEffect.DEVICE_SYSTEM_MUTATION), c.footprint.undeterminedEffects)
            assertTrue(c.footprint.effects.isEmpty())
        }
    }

    private fun request(args: String = "{}"): ToolDispatchRequest =
        ToolDispatchRequest(
            toolCallId = "call-1",
            turnId = "turn-1",
            sessionId = "session-1",
            toolName = ToolName("x"),
            toolVersion = ToolVersion(1),
            args = Json.parseToJsonElement(args).jsonObject,
            mode = AgentMode.ACT,
            profile = SafetyProfile.STANDARD,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            dataOrigin = DataOrigin.WORKSPACE,
            scope = null,
            uiToken = "ui:card:1",
        )

    private fun descriptor(
        name: String,
        operationClass: ToolOperationClass = ToolOperationClass.READ_ONLY,
        baseRisk: RiskLevel = RiskLevel.L0,
        origin: ToolOrigin = ToolOrigin.BuiltInOrigin,
    ): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(name),
            version = ToolVersion(1),
            description = "test tool",
            inputSchema = Json.parseToJsonElement("""{"type":"object"}""").jsonObject,
            outputSchema = Json.parseToJsonElement("""{"type":"object"}""").jsonObject,
            operationClass = operationClass,
            baseRisk = baseRisk,
            timeout = 30.seconds,
            maxOutputBytes = 1024,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = origin,
        )
}
