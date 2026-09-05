package com.helix.core.storage.repository

import com.helix.core.storage.dao.McpCapabilityDao
import com.helix.core.storage.dao.McpServerDao
import com.helix.core.storage.entity.McpCapabilityEntity
import com.helix.core.storage.entity.McpServerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class McpConfigRepositoryTest {
    @Test
    fun httpServerConfigIsCanonicalAliasOnlyAndDisabledByDefault() {
        val dao = FakeMcpServerDao()
        val repository = McpServerRepository(dao)

        val saved =
            repository.registerHttp(
                McpHttpServerSpec(
                    id = "mcp-server-1",
                    endpoint = "HTTPS://MCP.Example.com/mcp",
                    authAlias = "mcp.server.1",
                ),
            )

        assertEquals("streamable-http", saved.transport)
        assertEquals("https://mcp.example.com:443/mcp", saved.endpointRef)
        assertEquals("mcp.server.1", saved.authAlias)
        assertEquals(null, saved.commandRef)
        assertEquals("UNTRUSTED", saved.trustState)
        assertFalse(saved.enabled)
        assertFalse(saved.toString().contains("Bearer"))
    }

    @Test
    fun httpServerConfigRejectsPublicCleartextAndInvalidAlias() {
        val repository = McpServerRepository(FakeMcpServerDao())

        assertThrows(IllegalArgumentException::class.java) {
            repository.registerHttp(McpHttpServerSpec("mcp-public", "http://example.com/mcp", null))
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.registerHttp(McpHttpServerSpec("mcp-secret", "https://example.com/mcp", "../secret"))
        }
    }

    @Test
    fun snapshotPreservesOnlyUnchangedEnabledTools() {
        val dao = FakeMcpCapabilityDao()
        val repository = McpCapabilityRepository(dao)
        val initial =
            listOf(
                spec(McpCapabilityKind.TOOL, "read", 'a'),
                spec(McpCapabilityKind.RESOURCE, "docs", 'b'),
                spec(McpCapabilityKind.PROMPT, "review", 'c'),
            )

        val first = repository.replaceSnapshot("mcp-server-1", initial)
        assertTrue(first.none { it.enabled })
        dao.setEnabled(first.single { it.kind == "tool" }.rowId, true)
        dao.setEnabled(first.single { it.kind == "resource" }.rowId, true)

        val unchanged = repository.replaceSnapshot("mcp-server-1", initial)
        assertTrue(unchanged.single { it.kind == "tool" }.enabled)
        assertFalse(unchanged.single { it.kind == "resource" }.enabled)
        assertFalse(unchanged.single { it.kind == "prompt" }.enabled)

        val changed = repository.replaceSnapshot("mcp-server-1", listOf(spec(McpCapabilityKind.TOOL, "read", 'd')))
        assertFalse(changed.single().enabled)
        assertEquals("d".repeat(64), changed.single().schemaHash)
    }

    @Test
    fun invalidSnapshotDoesNotDeleteThePreviousSnapshot() {
        val dao = FakeMcpCapabilityDao()
        val repository = McpCapabilityRepository(dao)
        repository.replaceSnapshot("mcp-server-1", listOf(spec(McpCapabilityKind.TOOL, "read", 'a')))

        assertThrows(IllegalArgumentException::class.java) {
            repository.replaceSnapshot(
                "mcp-server-1",
                listOf(spec(McpCapabilityKind.TOOL, "duplicate", 'b'), spec(McpCapabilityKind.TOOL, "duplicate", 'c')),
            )
        }
        assertEquals("read", repository.listByServer("mcp-server-1").single().name)
    }

    private fun spec(
        kind: McpCapabilityKind,
        name: String,
        hashChar: Char,
    ): McpCapabilitySpec =
        McpCapabilitySpec(
            protocolVersion = "2025-03-26",
            kind = kind,
            name = name,
            contentHash = hashChar.toString().repeat(64),
        )
}

private class FakeMcpServerDao : McpServerDao {
    private val rows = linkedMapOf<String, McpServerEntity>()

    override fun insert(server: McpServerEntity) {
        check(rows.putIfAbsent(server.id, server) == null)
    }

    override fun byId(id: String): McpServerEntity? = rows[id]

    override fun list(): List<McpServerEntity> = rows.values.toList()

    override fun delete(id: String): Int = if (rows.remove(id) != null) 1 else 0

    override fun update(
        id: String,
        enabled: Boolean,
        trustState: String,
    ) {
        rows[id] = checkNotNull(rows[id]).copy(enabled = enabled, trustState = trustState)
    }
}

private class FakeMcpCapabilityDao : McpCapabilityDao {
    private val rows = mutableListOf<McpCapabilityEntity>()
    private var nextId = 1L

    override fun insert(capability: McpCapabilityEntity): Long {
        val id = nextId++
        rows += capability.copy(rowId = id)
        return id
    }

    override fun insertAll(capabilities: List<McpCapabilityEntity>): List<Long> = capabilities.map(::insert)

    override fun listByServer(serverId: String): List<McpCapabilityEntity> = rows.filter { it.serverId == serverId }

    override fun deleteByServer(serverId: String) {
        rows.removeAll { it.serverId == serverId }
    }

    override fun setEnabled(
        rowId: Long,
        enabled: Boolean,
    ) {
        val index = rows.indexOfFirst { it.rowId == rowId }
        check(index >= 0)
        rows[index] = rows[index].copy(enabled = enabled)
    }
}
