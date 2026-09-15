package com.helix.app.approval

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolApprovalPreference
import com.helix.core.model.ToolApprovalPreferenceScope
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.core.policy.EffectiveToolPreference
import com.helix.core.policy.ToolApprovalReason
import com.helix.core.storage.dao.ToolApprovalPreferenceDao
import com.helix.core.storage.dao.ToolBaselineMetaDao
import com.helix.core.storage.dao.ToolRegistrationBaselineDao
import com.helix.core.storage.entity.ToolApprovalPreferenceEntity
import com.helix.core.storage.entity.ToolBaselineMetaEntity
import com.helix.core.storage.entity.ToolRegistrationBaselineEntity
import com.helix.core.storage.repository.ToolApprovalPreferenceRepository
import com.helix.core.storage.repository.ToolRegistrationBaselineRepository
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * HXA-201: the settings screen's tool-approval model — one row per registered tool name carrying
 * the effective state the Dispatcher itself re-resolves (ADR-0052 point 7), GLOBAL-scope writes
 * and restore-default through the single write service, and the provenance-preserving state
 * projection (unset is never shown as allowed; an invalidated allow visibly needs re-confirm).
 *
 * Like the sibling service test, this JVM test runs the REAL registry and the REAL service
 * against in-memory DAOs; real Room, the real dispatcher and restart behavior are the device
 * matrix's job, not this one's.
 */
class ToolApprovalSettingsModelTest {
    @Test
    fun rowsShowTheLatestVersionPerNameWithUnsetStateAndProvenance() {
        val registry = ToolRegistry()
        registry.register(descriptor("fake.a"))
        registry.register(descriptor("fake.a", version = 2))
        registry.register(descriptor("fake.b", baseRisk = RiskLevel.L2))
        registry.register(mcpDescriptor("mcp.demo.tool"))
        val model = model(registry)

        val rows = model.rows()
        assertEquals(listOf("fake.a", "fake.b", "mcp.demo.tool"), rows.map { it.toolName })
        val a = rows[0]
        assertEquals(2, a.version)
        assertEquals(ToolApprovalSettingsState.UNSET, a.state)
        assertEquals("built-in", a.originLabel)
        assertEquals("built-in", a.sourceRef)
        assertTrue(a.records.isEmpty())
        assertEquals(RiskLevel.L2, rows[1].baseRisk)
        assertEquals("mcp:demo", rows[2].originLabel)
        assertEquals("mcp:demo:2025-03-26:${MCP_SCHEMA_SHA}", rows[2].sourceRef)
    }

    @Test
    fun searchMatchesTheToolNameAndTheProviderCaseInsensitively() {
        val registry = ToolRegistry()
        registry.register(descriptor("fake.a"))
        registry.register(descriptor("fake.b", baseRisk = RiskLevel.L2))
        registry.register(mcpDescriptor("mcp.demo.tool"))
        val model = model(registry)

        assertEquals(listOf("mcp.demo.tool"), model.rows("MCP").map { it.toolName })
        assertEquals(listOf("fake.a", "fake.b"), model.rows("FAKE").map { it.toolName })
        assertTrue(model.rows("zzz-no-such-tool").isEmpty())
    }

    @Test
    fun settingAllowBindsTheRowCurrentContractInTheGlobalScope() {
        val model = model(ToolRegistry().also { it.register(descriptor("fake.a")) })
        val row = model.rows().single()

        val updated = model.setPreference(row, ToolApprovalPreference.ALLOW)

        assertEquals(ToolApprovalSettingsState.ALLOW, updated.state)
        val record = updated.records.single()
        assertEquals(ToolApprovalPreference.ALLOW, record.preference)
        assertEquals(ToolApprovalPreferenceScope.GLOBAL, record.scope)
        assertEquals(row.contractHash, record.contractHash)
    }

    @Test
    fun settingDenyBlocksAndRestoreDefaultResetsToUnset() {
        val model = model(ToolRegistry().also { it.register(descriptor("fake.a")) })
        val row = model.rows().single()

        val denied = model.setPreference(row, ToolApprovalPreference.DENY)
        assertEquals(ToolApprovalSettingsState.DENY, denied.state)
        assertNull(denied.records.single().contractHash)

        val reset = model.restoreDefault(denied)
        assertEquals(ToolApprovalSettingsState.UNSET, reset.state)
        assertTrue(reset.records.isEmpty())
    }

    @Test
    fun anAskPreferenceStoresNoContractHash() {
        val model = model(ToolRegistry().also { it.register(descriptor("fake.a")) })
        val row = model.rows().single()

        val updated = model.setPreference(row, ToolApprovalPreference.ASK)

        assertEquals(ToolApprovalSettingsState.ASK, updated.state)
        assertNull(updated.records.single().contractHash)
    }

    @Test
    fun aContractChangeInvalidatesTheStoredAllowIntoTheReConfirmState() {
        val registry = ToolRegistry()
        registry.register(descriptor("fake.a", version = 1))
        val model = model(registry)

        model.setPreference(model.rows().single(), ToolApprovalPreference.ALLOW)
        // A newer version of the tool changes the contract hash, invalidating the stored ALLOW.
        registry.register(descriptor("fake.a", version = 2))

        val row = model.rows().single()
        assertEquals(2, row.version)
        assertEquals(ToolApprovalSettingsState.ASK_INVALIDATED, row.state)
        assertEquals(ToolApprovalPreference.ALLOW, row.records.single().preference)
    }

    @Test
    fun theStateProjectionPreservesEveryProvenanceVariant() {
        assertEquals(ToolApprovalSettingsState.UNSET, settingsStateOf(EffectiveToolPreference.Unset))
        assertEquals(ToolApprovalSettingsState.ALLOW, settingsStateOf(EffectiveToolPreference.Allow))
        assertEquals(
            ToolApprovalSettingsState.ASK,
            settingsStateOf(EffectiveToolPreference.Ask(ToolApprovalReason.EXPLICIT)),
        )
        assertEquals(
            ToolApprovalSettingsState.ASK_INVALIDATED,
            settingsStateOf(EffectiveToolPreference.Ask(ToolApprovalReason.ALLOW_INVALIDATED)),
        )
        assertEquals(
            ToolApprovalSettingsState.ASK_NEW_DEFAULT,
            settingsStateOf(EffectiveToolPreference.Ask(ToolApprovalReason.NEW_DEFAULT)),
        )
        assertEquals(ToolApprovalSettingsState.DENY, settingsStateOf(EffectiveToolPreference.Deny))
    }

    // HXA-201 slice 2: the approval card saves by (sourceRef, toolName) identity. The
    // registry keys on (name, version), so the real cross-server collision is the same NAME
    // at different versions from two servers — the preference must never leak across servers.

    @Test
    fun setPreferenceForWritesTheExactIdentityAmongSameNameTools() {
        val registry = ToolRegistry()
        registry.register(mcpDescriptor("mcp.demo.tool", "srv-a"))
        // Same NAME, another server, a different (registry-legal) version.
        registry.register(mcpDescriptor("mcp.demo.tool", "srv-b", version = 2))
        val model = model(registry)
        val bRef = "mcp:srv-b:2025-03-26:${MCP_SCHEMA_SHA}"

        val updated = model.setPreferenceFor(bRef, "mcp.demo.tool", ToolApprovalPreference.DENY)

        assertEquals(bRef, updated?.sourceRef)
        assertEquals(ToolApprovalSettingsState.DENY, updated?.state)
        // The other server's same-named tool is untouched: still UNSET.
        val aRow = model.rowForIdentity("mcp:srv-a:2025-03-26:${MCP_SCHEMA_SHA}", "mcp.demo.tool")
        assertEquals(ToolApprovalSettingsState.UNSET, aRow?.state)
    }

    @Test
    fun setPreferenceForFailsClosedForAnUnregisteredTool() {
        val model = model(ToolRegistry().also { it.register(descriptor("fake.a")) })

        assertNull(model.setPreferenceFor("built-in", "not.registered", ToolApprovalPreference.ALLOW))
        // Same name, unknown origin: no row to bind to.
        assertNull(
            model.setPreferenceFor("mcp:ghost:2025-03-26:${MCP_SCHEMA_SHA}", "fake.a", ToolApprovalPreference.DENY),
        )
        // Nothing was written.
        assertEquals(ToolApprovalSettingsState.UNSET, model.rows().single().state)
    }

    @Test
    fun rowsListEachServerOfASharedNameSeparately() {
        val registry = ToolRegistry()
        registry.register(mcpDescriptor("mcp.demo.tool", "srv-a"))
        registry.register(mcpDescriptor("mcp.demo.tool", "srv-b", version = 2))
        val model = model(registry)

        val shared = model.rows().filter { it.toolName == "mcp.demo.tool" }
        assertEquals(2, shared.size)
        // Tied on name, ordered by sourceRef: srv-a sorts before srv-b.
        assertEquals(
            listOf("mcp:srv-a:2025-03-26:${MCP_SCHEMA_SHA}", "mcp:srv-b:2025-03-26:${MCP_SCHEMA_SHA}"),
            shared.map { it.sourceRef },
        )
        // The row keeps its own server's version, not the global max.
        assertEquals(listOf(1, 2), shared.map { it.version })
    }

    private fun model(registry: ToolRegistry) =
        ToolApprovalSettingsModel(
            registry,
            ToolApprovalPreferenceService(
                ToolApprovalPreferenceRepository(InMemoryPreferenceDao()),
                ToolRegistrationBaselineRepository(InMemoryBaselineDao(), InMemoryMetaDao()),
                2L,
            ),
            { 1000L },
        )

    private fun descriptor(
        name: String,
        version: Int = 1,
        baseRisk: RiskLevel = RiskLevel.L1,
        origin: ToolOrigin = ToolOrigin.BuiltInOrigin,
    ): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(name),
            version = ToolVersion(version),
            description = "test tool $name",
            inputSchema = Json.parseToJsonElement("""{"type":"object"}""").jsonObject,
            outputSchema = Json.parseToJsonElement("""{"type":"object"}""").jsonObject,
            operationClass = ToolOperationClass.LOCAL_MUTATION,
            baseRisk = baseRisk,
            timeout = 30.seconds,
            maxOutputBytes = 1024L,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = origin,
        )

    private fun mcpDescriptor(
        name: String,
        serverId: String = "demo",
        version: Int = 1,
    ): ToolDescriptor =
        descriptor(name, version = version, origin = ToolOrigin.McpOrigin(serverId, "2025-03-26", MCP_SCHEMA_SHA))

    companion object {
        private const val MCP_SCHEMA_SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }

    // In-memory fakes of the Room DAOs — copied from the sibling service test: the unique
    // (source, tool, scopeKind, scopeRef) key, the rowid-ordered byTool and the first-write-wins
    // baseline inserts are the facts the service's paths rely on.
    private class InMemoryPreferenceDao : ToolApprovalPreferenceDao {
        private val rows = LinkedHashMap<String, ToolApprovalPreferenceEntity>()

        private fun key(e: ToolApprovalPreferenceEntity) = "${e.sourceRef}|${e.toolName}|${e.scopeKind}|${e.scopeRef}"

        override fun insert(entity: ToolApprovalPreferenceEntity) {
            check(key(entity) !in rows) { "ABORT: duplicate preference row" }
            rows[key(entity)] = entity
        }

        override fun update(
            id: String,
            preference: String,
            contractHash: String,
            revision: Long,
            updatedAtEpoch: Long,
        ) {
            val existing = rows.values.first { it.id == id }
            rows[key(existing)] =
                existing.copy(
                    preference = preference,
                    contractHash = contractHash,
                    revision = revision,
                    updatedAtEpoch = updatedAtEpoch,
                )
        }

        override fun byKey(
            sourceRef: String,
            toolName: String,
            scopeKind: String,
            scopeRef: String,
        ): ToolApprovalPreferenceEntity? =
            rows.values.firstOrNull {
                it.sourceRef == sourceRef &&
                    it.toolName == toolName &&
                    it.scopeKind == scopeKind &&
                    it.scopeRef == scopeRef
            }

        override fun byTool(
            sourceRef: String,
            toolName: String,
        ): List<ToolApprovalPreferenceEntity> =
            rows.values
                .filter { it.sourceRef == sourceRef && it.toolName == toolName }
                .sortedBy { it.scopeKind } // stable: keeps rowid order within one scopeKind

        override fun countByTool(
            sourceRef: String,
            toolName: String,
        ): Int = rows.values.count { it.sourceRef == sourceRef && it.toolName == toolName }

        override fun deleteByScope(
            sourceRef: String,
            toolName: String,
            scopeKind: String,
            scopeRef: String,
        ): Int {
            val match =
                rows.entries.firstOrNull {
                    it.value.sourceRef == sourceRef &&
                        it.value.toolName == toolName &&
                        it.value.scopeKind == scopeKind &&
                        it.value.scopeRef == scopeRef
                }
            return if (match != null) {
                rows.remove(match.key)
                1
            } else {
                0
            }
        }
    }

    private class InMemoryBaselineDao : ToolRegistrationBaselineDao {
        private val rows = LinkedHashMap<String, ToolRegistrationBaselineEntity>()

        private fun key(e: ToolRegistrationBaselineEntity) = "${e.sourceRef}|${e.toolName}"

        override fun insertIgnore(entity: ToolRegistrationBaselineEntity) {
            rows.putIfAbsent(key(entity), entity)
        }

        override fun firstSeenVersionCode(
            sourceRef: String,
            toolName: String,
        ): Long? =
            rows.values
                .firstOrNull { it.sourceRef == sourceRef && it.toolName == toolName }
                ?.firstSeenVersionCode
    }

    private class InMemoryMetaDao : ToolBaselineMetaDao {
        private val rows = LinkedHashMap<String, ToolBaselineMetaEntity>()

        override fun insertIgnore(entity: ToolBaselineMetaEntity) {
            rows.putIfAbsent(entity.id, entity)
        }

        override fun byId(id: String): ToolBaselineMetaEntity? = rows[id]
    }
}
