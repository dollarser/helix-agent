package com.helix.app

import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.profile.AdvancedProfileAvailability
import com.helix.app.provider.InAppMcpServer
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolAvailabilityScope
import com.helix.core.model.ToolCallState
import com.helix.core.policy.SessionPermissionConfig
import com.helix.core.policy.SsrfDenialCode
import com.helix.extensions.mcp.McpEndpointDeniedException
import com.helix.extensions.skills.connector.ConnectorPackageReader
import com.helix.tools.framework.DispatchOutcomeCode
import com.helix.tools.framework.ToolDispatchOutcome
import com.helix.tools.framework.ToolOrigin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * HXA-207 device acceptance: the EXISTING extension sources (Skill, MCP, Connector) close the
 * add -> preview -> configure -> explicit enable -> one real call loop through the UNIFIED
 * registry / discovery / authorization / execution pipeline, and connect/install success never
 * means enabled. Every test drives the production stack (`HelixApplication.appContainer`, the
 * storage-backed broker, the real dispatcher through [com.helix.app.chat.ChatService.dispatchToolCall])
 * and asserts the SIDE-EFFECT facts (what ran, what is exposed, what is the durable state) plus
 * the authorization decision, never the outcome code alone.
 *
 * Scenarios (task spec):
 *  - content changed after preview: a stale preview hash must reject the install; a fresh one commits.
 *  - import cancelled: a cancelled import throws before anything is committed.
 *  - double-click: a second identical connector import is idempotent (one record, not two).
 *  - invalid config: a config whose only endpoint is non-portable installs nothing.
 *  - connected but not enabled: a real successful handshake (developer build) that is never
 *    enabled exposes no tool and the execution entry rejects the name (UNKNOWN_TOOL); on the
 *    consumer build the unified SSRF gate instead DENIES the loopback endpoint under STANDARD
 *    (ADR-0005), so "connected" is unreachable and every surface is refused the same way.
 *  - enabled + real call (developer lane): an enabled MCP tool is exposed by discovery and the
 *    shared availability predicate, executes card-free under FULL_ACCESS, and returns the
 *    fixture's fixed readable text.
 *  - enabled but call fails (developer lane): once the server dies the still-exposed tool reports
 *    ExecutionFailed (a transport failure is NOT a confirmed side-effect-free failure, so no
 *    bounded retry).
 *  - repaired in place (developer lane): the failed server is repaired through the EXISTING user
 *    path (drop the dead endpoint, re-register the same server id at the recovered address,
 *    re-test, re-enable) and the same tool name answers again with the readable result.
 *  - tool disabled / operation ask (developer lane): a tool-level disable is refused at every
 *    surface (schema, discovery, execution entry) before any card even under FULL_ACCESS; under
 *    READ_ONLY the same un-determined remote operation instead ASKS for an approval card, which a
 *    denial refuses.
 *  - actual scope after restart: two explicit `am instrument` invocations (seed then recover,
 *    separated by a force-stop in the driver) prove skill enablement and the session permission mode
 *    PERSIST across a process restart while the in-memory MCP connection does not auto-re-enable.
 *
 * The loopback MCP lane exists only in the developer build: it is the user's own actions (switch
 * the persisted profile to ADVANCED, add the exact host:port LAN scope) that the connection-time
 * SSRF gate requires, and each lane restores both on exit. The consumer build refuses ADVANCED
 * outright (ADR-0005), so its acceptance is the consistent-denial surface, not a loopback call.
 *
 * Real external services are HXA-125; the in-APK [InAppMcpServer] is a deterministic local
 * fixture (clearly synthetic) and does NOT stand in for account acceptance.
 *
 * @see AdvancedProfileAvailability for the per-build lane availability.
 */
@RunWith(AndroidJUnit4::class)
@Suppress("TooManyFunctions") // Twelve closed-loop scenarios plus the shared fixture/settle harness.
class ExtensionJourneyDeviceTest {
    private lateinit var app: HelixApplication
    private lateinit var container: AppContainer

    /** Per-run suffix: the device Room persists across test runs, so ids must be unique. */
    private val run = System.nanoTime()

    /** The session this test opened (if any); settled by the backstop so a mid-approval death frees its slot. */
    private var trackedSession: String? = null

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<HelixApplication>()
        container = app.appContainer
    }

    @After
    fun settleTrackedSession() {
        trackedSession?.let { runCatching { settle(it) } }
        trackedSession = null
    }

    // =============================================================================================
    //  Shared helpers
    // =============================================================================================

    /** Seeds a fresh session row (the turn FK + open-session scoping need it), opens it, and tracks it. */
    private fun newSession(tag: String): String {
        val sessionId = "ext-$tag-$run"
        val now = System.currentTimeMillis()
        if (container.storage.sessions
                .list()
                .none { it.id == sessionId }
        ) {
            container.storage.sessions.create(sessionId, "extension journey $tag", null, null, now)
        }
        trackedSession = sessionId
        container.chatService.openSession(sessionId)
        return sessionId
    }

    /** Sets the session's ACTIVE config to a preset (a user change, audited by the write service). */
    private fun saveConfig(
        sessionId: String,
        mode: SessionPermissionMode,
    ) {
        container.sessionPermissionEdit.saveSessionConfig(
            sessionId,
            SessionPermissionConfig.of(mode),
            System.currentTimeMillis(),
        )
    }

    private class DispatchHandle(
        val latch: CountDownLatch,
        val outcome: Array<ToolDispatchOutcome?>,
        val error: Array<Throwable?>,
    ) {
        fun join(): ToolDispatchOutcome {
            assertTrue("dispatch must finish", latch.await(30, TimeUnit.SECONDS))
            error[0]?.let { throw it }
            return outcome[0] ?: error("no outcome")
        }
    }

    /**
     * Runs one dispatch through the production per-call pipeline on a worker thread (the broker
     * blocks on a pending decision, so this must never run on the main instrumentation thread).
     */
    private fun dispatchOnThread(
        sessionId: String,
        toolCallId: String,
        turnId: String,
        toolName: String,
        argsJson: String,
    ): DispatchHandle {
        val now = System.currentTimeMillis()
        if (container.storage.turns
                .listBySession(sessionId)
                .none { it.id == turnId }
        ) {
            container.storage.turns.start(turnId, sessionId, now)
        }
        val latch = CountDownLatch(1)
        val outcome = arrayOf<ToolDispatchOutcome?>(null)
        val error = arrayOf<Throwable?>(null)
        val thread =
            Thread {
                try {
                    outcome[0] =
                        container.chatService.dispatchToolCall(toolCallId, turnId, toolName, argsJson)
                } catch (e: Throwable) {
                    error[0] = e
                } finally {
                    latch.countDown()
                }
            }
        thread.isDaemon = true
        thread.start()
        return DispatchHandle(latch, outcome, error)
    }

    /** Polls the storage-backed approval record (the source of truth) until the broker creates it. */
    private fun approvalIdOf(toolCallId: String): String {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            container.storage.approvals
                .byToolCall(toolCallId)
                ?.let { return it.id }
            Thread.sleep(50)
        }
        error("no pending approval record for $toolCallId")
    }

    /** Backstop: cancel any approval still pending on [sessionId] and release the open session. */
    private fun settle(sessionId: String) {
        container.storage.turns
            .listBySession(sessionId)
            .flatMap { turn ->
                container.storage.toolCalls
                    .listByTurn(turn.id)
                    .filter { it.state == ToolCallState.AWAITING_APPROVAL.name }
                    .mapNotNull {
                        container.storage.approvals
                            .byToolCall(it.callId)
                            ?.id
                    }
            }.forEach { container.toolPipeline.broker.cancel(it) }
        runCatching { container.chatService.closeSession() }
    }

    /** The MCP descriptors currently registered for [serverId] in the shared registry. */
    private fun mcpDescriptors(serverId: String): List<com.helix.tools.framework.ToolDescriptor> =
        container.toolPipeline.registry.all().filter {
            it.origin is ToolOrigin.McpOrigin && it.name.value.startsWith("mcp.$serverId.")
        }

    /**
     * The developer build's loopback lane: the user's own two actions the connection-time SSRF
     * gate requires (switch the persisted profile to ADVANCED, then add the exact host:port LAN
     * scope for the fixture's port). [block] runs with both in force; on exit the scope is
     * removed and the previous profile restored so a later test/class starts from the same state.
     * Consumer builds refuse ADVANCED outright (ADR-0005), so this lane skips there.
     */
    private fun <T> advancedLoopbackLane(
        port: Int,
        block: () -> T,
    ): T {
        assumeTrue(
            "the loopback MCP lane requires the developer build's ADVANCED profile",
            AdvancedProfileAvailability.ADVANCED_AVAILABLE,
        )
        val previous = container.profileStore.profile
        val scope = "http://127.0.0.1:$port"
        container.profileStore.switchTo(SafetyProfile.ADVANCED)
        container.lanScopeStore.add(scope)
        try {
            return block()
        } finally {
            container.lanScopeStore.remove(scope)
            container.profileStore.switchTo(previous)
        }
    }

    /**
     * Brings up a REAL in-APK MCP server and registers it (DISABLED) under [serverId]. The
     * connection-time gate re-runs on EVERY call, so the caller keeps the developer loopback
     * lane open for the handshake, the explicit enable and all dispatches. The returned server
     * is closed (idempotently) by the caller.
     */
    private fun startMcpFixture(serverId: String): InAppMcpServer {
        val mcp = InAppMcpServer()
        mcp.start()
        container.mcpService.registerDisabled(serverId, "http://127.0.0.1:${mcp.port}/mcp", null)
        return mcp
    }

    /** The real handshake plus the explicit user ENABLE of the single fixture tool (in-lane only). */
    private fun handshakeAndEnable(serverId: String) {
        val snapshot = runBlocking { container.mcpService.testConnection(serverId) }
        container.mcpService.enable(snapshot, setOf(InAppMcpServer.TOOL_NAME))
    }

    /** The full name the production MCP source gives the fixture tool under [serverId]. */
    private fun mcpToolFullName(serverId: String) = "mcp.$serverId.${InAppMcpServer.TOOL_NAME}"

    /** A portable connector fixture: one skill plus one https MCP endpoint. */
    private fun connectorFixture(name: String): Map<String, ByteArray> =
        mapOf(
            ".codex-plugin/plugin.json" to """{"name":"$name"}""".toByteArray(),
            ".mcp.json" to """{"mcp_servers":{"docs":{"url":"https://example.com/mcp"}}}""".toByteArray(),
            "skills/$name/SKILL.md" to
                "---\nname: $name\ndescription: Journey fixture\n---\nRead selected documents.\n".toByteArray(),
        )

    /** Removes a draft skill directory (workspace bytes) so it does not linger on the device. */
    private fun cleanupSkillDraft(name: String) {
        val root = app.filesDir.toPath().resolve("workspaces/app/work/skills/$name")
        if (Files.exists(root)) {
            Files.walk(root).sorted(Comparator.reverseOrder()).use { paths ->
                paths.forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun markerPath(): Path = app.filesDir.toPath().resolve("extension-journey-restart.txt")

    // =============================================================================================
    //  Skill import: preview-change, cancel
    // =============================================================================================

    @Test
    @Suppress("LongMethod") // One preview->change->install journey with deterministic cleanup.
    fun contentChangedAfterPreviewRejectsInstall() {
        val name = "ext-content-$run"
        val author = requireNotNull(container.skillAuthoringService)
        val installation = requireNotNull(container.skillInstallationService)
        val path = author.saveDraft(name, "content changed", "Body")
        try {
            val staleHash = author.preview(path).snapshotHash
            // The draft changes AFTER the preview: the stale hash no longer describes the bytes.
            val draft = author.loadDraft(path)
            author.saveEditedDraft(path, draft.manifest.replace("Body", "Changed"), draft.contentHash)
            val e = assertThrows(IllegalArgumentException::class.java) { installation.install(path, staleHash) }
            assertTrue(
                "expected SKILL_CONTENT_CHANGED, got: ${e.message}",
                e.message?.contains("SKILL_CONTENT_CHANGED") == true,
            )
            assertFalse(
                "a changed draft must not be committed under a stale preview",
                container.skillRepository.list().any { it.key.name == name },
            )
            // A fresh preview (matching the new bytes) commits the install.
            val freshHash = author.preview(path).snapshotHash
            installation.install(path, freshHash)
            assertTrue(
                "a fresh preview must commit the install",
                container.skillRepository.list().any { it.key.name == name },
            )
        } finally {
            container.skillRepository.list().filter { it.key.name == name }.forEach {
                container.skillRepository.removePermanentlyForPrivacy(it.key)
            }
            cleanupSkillDraft(name)
        }
    }

    @Test
    fun importCancelledDoesNotCommit() {
        val name = "ext-cancel-$run"
        val author = requireNotNull(container.skillAuthoringService)
        val installation = requireNotNull(container.skillInstallationService)
        val path = author.saveDraft(name, "cancelled", "Body")
        try {
            val hash = author.preview(path).snapshotHash
            val e =
                assertThrows(IllegalStateException::class.java) {
                    installation.install(path, hash, cancelled = { true })
                }
            assertTrue(
                "expected IMPORT_CANCELLED, got: ${e.message}",
                e.message?.contains("IMPORT_CANCELLED") == true,
            )
            assertFalse(
                "a cancelled import must not register the skill",
                container.skillRepository.list().any { it.key.name == name },
            )
        } finally {
            cleanupSkillDraft(name)
        }
    }

    // =============================================================================================
    //  Connector import: double-click (idempotent) + invalid config
    // =============================================================================================

    @Test
    fun duplicateImportIsIdempotent() {
        val service = container.connectorService
        val name = "ext-dup-$run"
        val bundle = ConnectorPackageReader().parse(connectorFixture(name))
        val before = service.list().size
        try {
            val first = service.install(bundle)
            val second = service.install(bundle)
            assertEquals("a second identical import must be a no-op (idempotent)", first.id, second.id)
            assertEquals(
                "one content hash yields exactly one connector record",
                1,
                service.list().count { it.hash == bundle.contentHash },
            )
            assertEquals(before + 1, service.list().size)
        } finally {
            service.list().filter { it.hash == bundle.contentHash }.forEach { service.remove(it) }
            container.skillRepository.list().filter { it.key.name == name }.forEach {
                container.skillRepository.removePermanentlyForPrivacy(it.key)
            }
        }
    }

    @Test
    fun invalidConfigRejectsInstall() {
        val service = container.connectorService
        val name = "ext-invalid-$run"
        val before = service.list().size
        // The endpoint is configured but NOT portable (plain http, not https): the reader keeps it
        // only as a diagnostic, so the bundle carries no installable component.
        val bundle =
            ConnectorPackageReader().parse(
                mapOf(
                    ".codex-plugin/plugin.json" to """{"name":"$name"}""".toByteArray(),
                    ".mcp.json" to """{"docs":{"url":"http://127.0.0.1:9999/mcp"}}""".toByteArray(),
                ),
            )
        assertTrue("a non-portable endpoint yields no installable endpoint", bundle.endpoints.isEmpty())
        val e = assertThrows(IllegalArgumentException::class.java) { service.install(bundle) }
        assertTrue(
            "expected CONNECTOR_NO_PORTABLE_COMPONENT, got: ${e.message}",
            e.message?.contains("CONNECTOR_NO_PORTABLE_COMPONENT") == true,
        )
        assertEquals("nothing is installed for an invalid config", before, service.list().size)
    }

    // =============================================================================================
    //  MCP: connected-but-not-enabled, enabled + real call, enabled but call fails
    // =============================================================================================

    @Test
    fun connectedButNotEnabledIsInvisibleAndRejected() {
        val serverId = "ext-mcp-off-$run"
        val sessionId = newSession("mcpoff")
        val mcp = InAppMcpServer()
        mcp.start()
        try {
            container.mcpService.registerDisabled(serverId, "http://127.0.0.1:${mcp.port}/mcp", null)
            if (AdvancedProfileAvailability.ADVANCED_AVAILABLE) {
                // DEVELOPER: a REAL successful handshake (the server is reachable) but the user
                // never enables it.
                advancedLoopbackLane(mcp.port) {
                    val snapshot = runBlocking { container.mcpService.testConnection(serverId) }
                    assertEquals(
                        InAppMcpServer.TOOL_NAME,
                        snapshot.metadata.tools
                            .single()
                            .name,
                    )
                }
            } else {
                // CONSUMER (STANDARD, ADR-0005): the unified connection-time gate denies loopback
                // egress outright, so "connected" is unreachable here — and the denial is the SAME
                // stable refusal the product shows at the config surface.
                val e =
                    assertThrows(McpEndpointDeniedException::class.java) {
                        runBlocking { container.mcpService.testConnection(serverId) }
                    }
                assertEquals(
                    "consumer (STANDARD) must deny loopback with the stable gate code",
                    com.helix.core.policy.SsrfDenialCode.LAN_NOT_ALLOWED,
                    e.code,
                )
            }
            // Connected-but-NOT-enabled (or refused, on consumer): no live bridge, no registered
            // tool, not active.
            assertFalse(container.mcpService.isActive(serverId))
            assertTrue("a connected-but-unenabled server registers no tool", mcpDescriptors(serverId).isEmpty())
            val toolFullName = mcpToolFullName(serverId)
            assertFalse(
                "an unenabled MCP tool must not be exposed by discovery",
                container.toolPipeline.mcpDiscovery
                    .visible(sessionId, container.toolPipeline.registry.all())
                    .any { it.name.value == toolFullName },
            )
            // And the execution entry rejects the unregistered tool name.
            val outcome =
                dispatchOnThread(
                    sessionId,
                    "ext-mcpoff-call-$run",
                    "ext-mcpoff-turn-$run",
                    toolFullName,
                    """{"case":"x"}""",
                ).join()
            assertEquals(DispatchOutcomeCode.UNKNOWN_TOOL, (outcome as ToolDispatchOutcome.Denied).code)
            assertNull(
                "no approval card is created for an unregistered tool",
                container.storage.approvals.byToolCall("ext-mcpoff-call-$run"),
            )
        } finally {
            container.mcpService.delete(serverId)
            mcp.close()
        }
    }

    @Test
    @Suppress("LongMethod") // Enable -> expose -> real call -> readable result, with cleanup.
    fun enabledAndCallSucceedsWithReadableResult() {
        assumeTrue(
            "an enabled loopback MCP call needs the developer lane",
            AdvancedProfileAvailability.ADVANCED_AVAILABLE,
        )
        val serverId = "ext-mcp-ok-$run"
        val sessionId = newSession("mcpok")
        val mcp = startMcpFixture(serverId)
        try {
            // The gate re-authorizes on every call, so the lane spans handshake, enable and dispatch.
            advancedLoopbackLane(mcp.port) {
                handshakeAndEnable(serverId)
                saveConfig(sessionId, SessionPermissionMode.FULL_ACCESS)
                val toolFullName = mcpToolFullName(serverId)
                val descriptor = mcpDescriptors(serverId).single()
                assertEquals(toolFullName, descriptor.name.value)
                // Discovery and the shared availability predicate (the SAME one the model schema and
                // the execution entry use) both admit the enabled tool.
                assertTrue(
                    "the enabled tool must be exposed by discovery",
                    container.toolPipeline.mcpDiscovery
                        .visible(sessionId, container.toolPipeline.registry.all())
                        .any { it.name.value == toolFullName },
                )
                assertTrue(
                    "the shared availability predicate must admit the enabled tool",
                    requireNotNull(container.toolPipeline.disabledToolFilter).invoke(sessionId, descriptor),
                )
                val outcome =
                    dispatchOnThread(
                        sessionId,
                        "ext-mcpok-call-$run",
                        "ext-mcpok-turn-$run",
                        toolFullName,
                        """{"case":"ext-$run"}""",
                    ).join()
                assertTrue("expected Succeeded, got: $outcome", outcome is ToolDispatchOutcome.Succeeded)
                // The model-visible result carries the fixture's fixed readable text.
                assertTrue(
                    "the readable result must carry the fixture text",
                    (outcome as ToolDispatchOutcome.Succeeded).result.payload.contains(InAppMcpServer.READ_TEXT),
                )
                assertEquals(
                    ToolCallState.COMPLETED.name,
                    container.storage.toolCalls
                        .resolve("ext-mcpok-call-$run")
                        .state,
                )
                // The same result is durably persisted for the turn (a second, independent read-back).
                val persisted =
                    container.storage.toolResults.byToolCall("ext-mcpok-call-$run")?.let {
                        container.storage.toolResults.readContent(it)
                    }
                assertTrue(
                    "the persisted result must carry the fixture text",
                    !persisted.isNullOrBlank() && persisted.contains(InAppMcpServer.READ_TEXT),
                )
            }
        } finally {
            container.mcpService.delete(serverId)
            mcp.close()
        }
    }

    @Test
    fun enabledButCallFails() {
        assumeTrue(
            "an enabled loopback MCP call needs the developer lane",
            AdvancedProfileAvailability.ADVANCED_AVAILABLE,
        )
        val serverId = "ext-mcp-fail-$run"
        val sessionId = newSession("mcpfail")
        val mcp = startMcpFixture(serverId)
        try {
            advancedLoopbackLane(mcp.port) {
                handshakeAndEnable(serverId)
                saveConfig(sessionId, SessionPermissionMode.FULL_ACCESS)
                val toolFullName = mcpToolFullName(serverId)
                // The tool WAS exposed (registered + visible) before the call.
                assertTrue(mcpDescriptors(serverId).any { it.name.value == toolFullName })
                // Now the server dies: the still-exposed tool can no longer reach it. The lane stays
                // open (the endpoint is still authorized), so the failure is a TRANSPORT failure,
                // not a gate denial.
                mcp.close()
                val outcome =
                    dispatchOnThread(
                        sessionId,
                        "ext-mcpfail-call-$run",
                        "ext-mcpfail-turn-$run",
                        toolFullName,
                        """{"case":"x"}""",
                    ).join()
                assertTrue("expected ExecutionFailed, got: $outcome", outcome is ToolDispatchOutcome.ExecutionFailed)
                // An MCP transport failure is NOT a confirmed side-effect-free failure (no bounded retry).
                assertFalse((outcome as ToolDispatchOutcome.ExecutionFailed).sideEffectFree)
            }
        } finally {
            container.mcpService.delete(serverId)
            mcp.close()
        }
    }

    // =============================================================================================
    //  MCP: repaired in place (fail -> existing user path -> call succeeds again)
    // =============================================================================================

    @Test
    @Suppress("LongMethod") // Fail -> in-place repair through the existing user path -> call again.
    fun aFailedMcpIsRepairedInPlace() {
        assumeTrue(
            "an enabled loopback MCP call needs the developer lane",
            AdvancedProfileAvailability.ADVANCED_AVAILABLE,
        )
        val serverId = "ext-mcp-fix-$run"
        val sessionId = newSession("mcpfix")
        val mcp = startMcpFixture(serverId)
        val recovered = InAppMcpServer()
        try {
            advancedLoopbackLane(mcp.port) {
                // Baseline: the enabled tool answers with the readable result.
                handshakeAndEnable(serverId)
                saveConfig(sessionId, SessionPermissionMode.FULL_ACCESS)
                val toolFullName = mcpToolFullName(serverId)
                val first =
                    dispatchOnThread(
                        sessionId,
                        "ext-mcpfix-call1-$run",
                        "ext-mcpfix-turn1-$run",
                        toolFullName,
                        """{"case":"x"}""",
                    ).join()
                assertTrue("the baseline call must succeed, got: $first", first is ToolDispatchOutcome.Succeeded)
                // The server dies: the same exposed tool now fails.
                mcp.close()
                val second =
                    dispatchOnThread(
                        sessionId,
                        "ext-mcpfix-call2-$run",
                        "ext-mcpfix-turn2-$run",
                        toolFullName,
                        """{"case":"x"}""",
                    ).join()
                assertTrue(
                    "the dead server must surface as a transport failure, got: $second",
                    second is ToolDispatchOutcome.ExecutionFailed,
                )
                // In-place repair through the EXISTING user path: the server is back at a NEW
                // address, so the user scopes that exact host:port (a second scope row), drops the
                // dead endpoint, re-registers the SAME server id at the recovered address,
                // re-tests, re-enables. No new service, no new state machine.
                recovered.start()
                val recoveredScope = "http://127.0.0.1:${recovered.port}"
                container.lanScopeStore.add(recoveredScope)
                try {
                    container.mcpService.delete(serverId)
                    container.mcpService.registerDisabled(serverId, "http://127.0.0.1:${recovered.port}/mcp", null)
                    handshakeAndEnable(serverId)
                    val third =
                        dispatchOnThread(
                            sessionId,
                            "ext-mcpfix-call3-$run",
                            "ext-mcpfix-turn3-$run",
                            toolFullName,
                            """{"case":"x"}""",
                        ).join()
                    assertTrue(
                        "the repaired tool must answer again, got: $third",
                        third is ToolDispatchOutcome.Succeeded,
                    )
                    assertTrue(
                        "the repaired result must carry the fixture text",
                        (third as ToolDispatchOutcome.Succeeded).result.payload.contains(InAppMcpServer.READ_TEXT),
                    )
                } finally {
                    container.lanScopeStore.remove(recoveredScope)
                }
            }
        } finally {
            runCatching { container.mcpService.delete(serverId) }
            mcp.close()
            recovered.close()
        }
    }

    // =============================================================================================
    //  MCP: tool disabled (refused at every surface) + operation ask (READ_ONLY)
    // =============================================================================================

    @Test
    @Suppress("LongMethod") // Disable -> refute at schema/discovery/execution entry, with cleanup.
    fun aDisabledMcpToolIsRefusedAtEverySurface() {
        assumeTrue(
            "an enabled loopback MCP tool needs the developer lane",
            AdvancedProfileAvailability.ADVANCED_AVAILABLE,
        )
        val serverId = "ext-mcp-dis-$run"
        val sessionId = newSession("mcpdis")
        val mcp = startMcpFixture(serverId)
        try {
            advancedLoopbackLane(mcp.port) {
                handshakeAndEnable(serverId)
                val toolFullName = mcpToolFullName(serverId)
                val descriptor = mcpDescriptors(serverId).single()
                val sourceRef = descriptor.origin.canonicalOf()
                val now = System.currentTimeMillis()
                // FULL_ACCESS would otherwise execute the tool; a tool-level disable is orthogonal.
                saveConfig(sessionId, SessionPermissionMode.FULL_ACCESS)
                assertTrue(
                    "disabling the tool must store a new availability row",
                    container.sessionPermissionEdit.setToolAvailability(
                        sourceRef,
                        toolFullName,
                        ToolAvailabilityScope.SESSION,
                        sessionId,
                        true,
                        now,
                    ),
                )
                // The shared predicate (model schema + discovery + execution entry) now refuses it.
                assertFalse(
                    "the shared availability predicate must refuse the disabled tool",
                    requireNotNull(container.toolPipeline.disabledToolFilter).invoke(sessionId, descriptor),
                )
                assertFalse(
                    "discovery must drop the disabled tool at the same moment",
                    container.toolPipeline.mcpDiscovery
                        .visible(sessionId, container.toolPipeline.registry.all())
                        .any { it.name.value == toolFullName },
                )
                // And the execution entry refuses it BEFORE any card, even under FULL_ACCESS.
                val outcome =
                    dispatchOnThread(
                        sessionId,
                        "ext-mcpdis-call-$run",
                        "ext-mcpdis-turn-$run",
                        toolFullName,
                        """{"case":"x"}""",
                    ).join()
                assertEquals(DispatchOutcomeCode.TOOL_DISABLED, (outcome as ToolDispatchOutcome.Denied).code)
                assertNull(
                    "a disabled tool stops BEFORE any card is created",
                    container.storage.approvals.byToolCall("ext-mcpdis-call-$run"),
                )
                assertEquals(
                    ToolCallState.DENIED.name,
                    container.storage.toolCalls
                        .resolve("ext-mcpdis-call-$run")
                        .state,
                )
            }
        } finally {
            container.mcpService.delete(serverId)
            mcp.close()
        }
    }

    @Test
    fun anMcpOperationAsksForApprovalUnderReadOnly() {
        assumeTrue(
            "an enabled loopback MCP tool needs the developer lane",
            AdvancedProfileAvailability.ADVANCED_AVAILABLE,
        )
        val serverId = "ext-mcp-ro-$run"
        val sessionId = newSession("mcp-ro")
        val mcp = startMcpFixture(serverId)
        try {
            // The lane stays open until the dispatch has ASKED and the denial has settled, because
            // the gate re-authorizes on the call itself.
            advancedLoopbackLane(mcp.port) {
                handshakeAndEnable(serverId)
                val toolFullName = mcpToolFullName(serverId)
                // READ_ONLY: an MCP origin is an UNDETERMINED remote mutation, so the ONE resolver ASKS.
                saveConfig(sessionId, SessionPermissionMode.READ_ONLY)
                val handle =
                    dispatchOnThread(
                        sessionId,
                        "ext-mcp-ro-call-$run",
                        "ext-mcp-ro-turn-$run",
                        toolFullName,
                        """{"case":"x"}""",
                    )
                // A real approval card is created; the user must resolve it (we deny it).
                val approvalId = approvalIdOf("ext-mcp-ro-call-$run")
                container.chatService.denyApproval(approvalId)
                val outcome = handle.join()
                assertEquals(DispatchOutcomeCode.APPROVAL_DENIED, (outcome as ToolDispatchOutcome.Denied).code)
                // A denied remote operation never executed.
                assertEquals(
                    ToolCallState.DENIED.name,
                    container.storage.toolCalls
                        .resolve("ext-mcp-ro-call-$run")
                        .state,
                )
            }
        } finally {
            container.mcpService.delete(serverId)
            mcp.close()
        }
    }

    // =============================================================================================
    //  Actual scope after a process restart (two explicit `am instrument` invocations)
    //
    //  The driver (scripts/debug/2026-09-18) runs SEED, force-stops the app (new process next
    //  launch, filesDir preserved), then runs RECOVER. In the ordinary matrix run both methods
    //  skip (no extensionJourneyPhase argument).
    // =============================================================================================

    @Test
    fun seedExtensionJourneyScope() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("extensionJourneyPhase") == "seed")
        val connectorService = container.connectorService
        val name = "ext-restart-${UUID.randomUUID()}"
        val bundle =
            ConnectorPackageReader().parse(
                mapOf(
                    ".codex-plugin/plugin.json" to """{"name":"$name"}""".toByteArray(),
                    "skills/$name/SKILL.md" to
                        "---\nname: $name\ndescription: Restart fixture\n---\nRead resources.\n".toByteArray(),
                    ".mcp.json" to """{"docs":{"url":"https://connector.invalid/mcp"}}""".toByteArray(),
                ),
            )
        val installed = connectorService.install(bundle)
        // The user explicitly enables the persisted skill (GLOBAL scope, a durable row).
        connectorService.setSkillEnabled(installed.skills.single(), true)
        // A non-default session permission mode is also a durable row keyed by the session.
        val sessionId = newSession("restart")
        saveConfig(sessionId, SessionPermissionMode.READ_ONLY)
        Files.write(markerPath(), "${installed.id}\n${Process.myPid()}\n$sessionId\n".toByteArray())
        assertTrue(
            "the seed phase must have enabled the persisted skill",
            connectorService.skillEnabled(installed.skills.single()),
        )
    }

    @Test
    fun recoverExtensionJourneyScope() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("extensionJourneyPhase") == "recover")
        val marker = markerPath()
        assumeTrue("the seed phase must have written the marker", Files.exists(marker))
        val lines = Files.readAllLines(marker)
        assertNotEquals("recover must run in a NEW process (a different pid)", lines[1].toInt(), Process.myPid())
        val connectorService = container.connectorService
        val record = connectorService.list().single { it.id == lines[0] }
        try {
            // Skill enablement is GLOBAL and persisted -> it survives the restart.
            assertTrue(
                "skill enablement must persist across the restart",
                connectorService.skillEnabled(record.skills.single()),
            )
            // The MCP connection is IN-MEMORY -> it is NOT auto-re-enabled after a restart.
            assertFalse(
                "the in-memory MCP connection must not auto-re-enable",
                connectorService.enabled(record.endpoints.single()),
            )
            // No live bridge means no registered MCP tool for the recorded endpoint.
            assertTrue(
                "no MCP tool is registered after the restart",
                container.toolPipeline.registry
                    .all()
                    .none { it.name.value.startsWith("mcp.${record.endpoints.single().id}.") },
            )
            // The session permission mode is persisted -> it is still the non-default mode.
            assertEquals(
                SessionPermissionMode.READ_ONLY,
                container.sessionPermissionEdit.activeConfigFor(lines[2])?.mode,
            )
        } finally {
            connectorService.remove(record)
            container.skillRepository.list().filter { it.key.name == record.skills.singleOrNull()?.name }.forEach {
                runCatching { container.skillRepository.removePermanentlyForPrivacy(it.key) }
            }
            Files.deleteIfExists(marker)
        }
    }
}
