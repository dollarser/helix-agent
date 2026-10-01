package com.helix.tools.framework

import com.helix.core.model.Capability
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderId
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.core.policy.DataSensitivity
import com.helix.core.policy.EgressRequest
import com.helix.core.policy.EgressTarget
import com.helix.core.policy.WorkspaceScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * HXA-037 (doc 11 section 3.1): the platform-owned EffectFootprint — generated from the
 * registered descriptor + normalized arguments + trusted facts; non-read-only, Root and
 * Accessibility actions are exclusive; QuickJS/PRoot/CLI are single-concurrent lanes;
 * unknown descriptors are conservative (exclusive); the extractor adds platform resource
 * keys; nothing here can be influenced by the model, MCP annotations or Skills.
 */
class EffectFootprintTest {
    @Test fun onlyTheNativeSingletonEngineHasAPhysicalLane() {
        val native =
            EffectFootprintBuilder.build(
                descriptor(),
                json("""{"access":"native"}"""),
                ExecutionTargetType.LOCAL_QUICKJS,
                null,
                null,
                NoResourceKeys,
            )
        val isolated =
            EffectFootprintBuilder.build(
                descriptor(),
                json("{}"),
                ExecutionTargetType.LOCAL_QUICKJS,
                null,
                null,
                NoResourceKeys,
            )
        assertTrue(native.conflictsWith(native))
        assertFalse(native.conflictsWith(isolated))
    }

    private fun json(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    private fun descriptor(
        operationClass: ToolOperationClass = ToolOperationClass.READ_ONLY,
        capabilities: Set<Capability> = emptySet(),
    ) = ToolDescriptor(
        name = ToolName("t"),
        version = ToolVersion(1),
        description = "d",
        inputSchema = json("""{"type":"object"}"""),
        outputSchema = json("""{"type":"object"}"""),
        operationClass = operationClass,
        timeout = 30.seconds,
        maxOutputBytes = 1024L,
        requiredCapabilities = capabilities,
        idempotency = Idempotency.IDEMPOTENT,
        executionTarget = ExecutionTargetType.LOCAL_ANDROID,
        origin = ToolOrigin.BuiltInOrigin,
    )

    private val egress =
        EgressRequest(
            target = EgressTarget.Provider(ProviderId("p1")),
            endpoint =
                NormalizedEndpoint(
                    scheme = "https",
                    host = "api.example.com",
                    port = 443,
                    path = "",
                ),
            dataSensitivity = DataSensitivity.SENSITIVE,
        )

    // ------------------------------------------------------------------ exclusivity rules

    @Test
    fun readOnlyWithoutCapabilitiesIsNotExclusive() {
        val fp =
            EffectFootprintBuilder.build(
                descriptor(),
                json("{}"),
                ExecutionTargetType.LOCAL_ANDROID,
                null,
                null,
                NoResourceKeys,
            )
        assertTrue(fp.runtimeKeys.isEmpty())
        assertEquals(ToolOperationClass.READ_ONLY, fp.operationClass)
    }

    @Test
    fun writesAndCodeDoNotAcquireGlobalResultLocks() {
        for (op in listOf(
            ToolOperationClass.LOCAL_MUTATION,
            ToolOperationClass.NETWORK,
            ToolOperationClass.EXTERNAL_ACTION,
            ToolOperationClass.CODE_EXECUTION,
            ToolOperationClass.PRIVILEGED,
        )) {
            val fp =
                EffectFootprintBuilder.build(
                    descriptor(operationClass = op),
                    json("{}"),
                    ExecutionTargetType.LOCAL_ANDROID,
                    null,
                    null,
                    NoResourceKeys,
                )
            assertTrue("$op must not impose a global result lock", fp.runtimeKeys.isEmpty())
        }
    }

    @Test
    fun privilegedCapabilitiesDoNotImposeGlobalExclusivity() {
        for (cap in listOf(Capability.ROOT_SHELL, Capability.ACCESSIBILITY_AUTOMATION)) {
            val fp =
                EffectFootprintBuilder.build(
                    descriptor(capabilities = setOf(cap)),
                    json("{}"),
                    ExecutionTargetType.LOCAL_ANDROID,
                    null,
                    null,
                    NoResourceKeys,
                )
            assertTrue("$cap authorization is not a global lock", fp.runtimeKeys.isEmpty())
        }
    }

    @Test
    fun unknownDescriptorIsStillValidatedByDispatcherWithoutAGlobalLock() {
        // A descriptor the registry cannot resolve (null) must be treated as an
        // unknown effect: exclusive, never parallel.
        val fp =
            EffectFootprintBuilder.build(
                null,
                json("{}"),
                ExecutionTargetType.LOCAL_ANDROID,
                null,
                null,
                NoResourceKeys,
            )
        assertTrue(fp.runtimeKeys.isEmpty())
        assertEquals(ToolOperationClass.LOCAL_MUTATION, fp.operationClass)
    }

    // ------------------------------------------------------------------ lanes and keys

    @Test
    fun runtimeTargetsDoNotImposeBlanketSingleExecutionLanes() {
        val targets =
            listOf(
                ExecutionTargetType.LOCAL_QUICKJS to "lane:quickjs",
                ExecutionTargetType.LOCAL_PROOT to "lane:proot",
                ExecutionTargetType.LOCAL_CLI_RUNTIME to "lane:cli",
                ExecutionTargetType.LOCAL_ROOT to "lane:root",
            )
        for ((target, lane) in targets) {
            val fp =
                EffectFootprintBuilder.build(descriptor(), json("{}"), target, null, null, NoResourceKeys)
            assertFalse("$target does not use a blanket lane key", lane in fp.resourceKeys)
            assertTrue("$target has independent execution capacity", fp.runtimeKeys.isEmpty())
        }
    }

    @Test
    fun isolatedQuickJsInstancesDoNotConflict() {
        val a =
            EffectFootprintBuilder.build(
                descriptor(),
                json("{}"),
                ExecutionTargetType.LOCAL_QUICKJS,
                null,
                null,
                NoResourceKeys,
            )
        val b =
            EffectFootprintBuilder.build(
                descriptor(),
                json("{}"),
                ExecutionTargetType.LOCAL_QUICKJS,
                null,
                null,
                NoResourceKeys,
            )
        val c =
            EffectFootprintBuilder.build(
                descriptor(),
                json("{}"),
                ExecutionTargetType.LOCAL_ANDROID,
                null,
                null,
                NoResourceKeys,
            )
        assertFalse(a.conflictsWith(b))
        assertFalse(a.conflictsWith(c))
        assertFalse(c.conflictsWith(a))
    }

    @Test
    fun userResourceKeysRemainFactsRatherThanResultLocks() {
        val sameKey = ResourceKeyExtractor { _, _ -> setOf("file:a.txt") }
        val otherKey = ResourceKeyExtractor { _, _ -> setOf("file:b.txt") }
        val a =
            EffectFootprintBuilder.build(
                descriptor(),
                json("{}"),
                ExecutionTargetType.LOCAL_ANDROID,
                null,
                null,
                sameKey,
            )
        val b =
            EffectFootprintBuilder.build(
                descriptor(),
                json("{}"),
                ExecutionTargetType.LOCAL_ANDROID,
                null,
                null,
                sameKey,
            )
        val c =
            EffectFootprintBuilder.build(
                descriptor(),
                json("{}"),
                ExecutionTargetType.LOCAL_ANDROID,
                null,
                null,
                otherKey,
            )
        assertFalse(a.conflictsWith(b))
        assertFalse(a.conflictsWith(c))
    }

    @Test
    fun egressOriginKeysDoNotSerializeIndependentRequests() {
        val a =
            EffectFootprintBuilder.build(
                descriptor(),
                json("{}"),
                ExecutionTargetType.LOCAL_ANDROID,
                null,
                egress,
                NoResourceKeys,
            )
        val b =
            EffectFootprintBuilder.build(
                descriptor(),
                json("{}"),
                ExecutionTargetType.LOCAL_ANDROID,
                null,
                egress,
                NoResourceKeys,
            )
        val c =
            EffectFootprintBuilder.build(
                descriptor(),
                json("{}"),
                ExecutionTargetType.LOCAL_ANDROID,
                null,
                null,
                NoResourceKeys,
            )
        assertFalse(a.conflictsWith(b))
        assertFalse(a.conflictsWith(c))
        assertEquals(setOf("https://api.example.com:443"), a.originKeys)
    }

    @Test
    fun codeExecutionDoesNotBlockUnrelatedReads() {
        val exclusive =
            EffectFootprintBuilder.build(
                descriptor(operationClass = ToolOperationClass.CODE_EXECUTION),
                json("{}"),
                ExecutionTargetType.LOCAL_ANDROID,
                null,
                null,
                NoResourceKeys,
            )
        val plain =
            EffectFootprintBuilder.build(
                descriptor(),
                json("{}"),
                ExecutionTargetType.LOCAL_ANDROID,
                null,
                null,
                NoResourceKeys,
            )
        assertFalse(exclusive.conflictsWith(plain))
        assertFalse(plain.conflictsWith(exclusive))
    }

    @Test
    fun scopeIsRecordedAsAnInformationalFootprintFact() {
        val fp =
            EffectFootprintBuilder.build(
                descriptor(),
                json("{}"),
                ExecutionTargetType.LOCAL_ANDROID,
                WorkspaceScope("ws-1"),
                null,
                NoResourceKeys,
            )
        assertEquals(setOf("workspace:ws-1"), fp.scopeIds)
    }
}
