package com.helix.tools.root

import com.helix.core.model.Capability
import com.helix.core.model.Clock
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RootToolsTest {
    private val port = FakeOperationPort()
    private val clock =
        object : Clock {
            override fun now() = Instant.parse("2026-09-05T00:00:00Z")
        }
    private val sessions = RootSessionManager(clock, port::status) {}
    private val tools = RootTools(port, sessions)

    @Test
    fun registryContainsOnlyFiveHighLevelReadsWithCorrectRiskAndTarget() {
        val descriptors = tools.descriptors()
        assertEquals(
            listOf("root.file.read", "root.log.read", "root.package.info", "root.process.list", "root.status"),
            descriptors.map { it.name.value }.sorted(),
        )
        assertFalse(descriptors.any { it.name.value == "root.exec" })
        assertTrue(descriptors.all { it.executionTarget == ExecutionTargetType.LOCAL_ROOT })
        assertEquals(RiskLevel.L0, descriptors.single { it.name.value == RootTools.STATUS }.baseRisk)
        assertTrue(descriptors.single { it.name.value == RootTools.STATUS }.requiredCapabilities.isEmpty())
        assertEquals(RiskLevel.L2, descriptors.single { it.name.value == RootTools.FILE_READ }.baseRisk)
        assertEquals(
            setOf(Capability.ROOT_SHELL),
            descriptors
                .single {
                    it.name.value == RootTools.FILE_READ
                }.requiredCapabilities,
        )
    }

    @Test
    fun statusIsObservationalAndDoesNotNeedSession() {
        val output = completed(execute(RootTools.STATUS, buildJsonObject {}))
        assertEquals("granted", output["grant"]?.jsonPrimitive?.content)
        assertEquals("inactive", output["session"]?.jsonPrimitive?.content)
        assertTrue(port.requests.isEmpty())
    }

    @Test
    fun fileReadResolvesOpaqueScopeAndReturnsBoundedUtf8() {
        sessions.start(mapOf("system" to "/system/etc"))
        port.next = RootOperationResult.File(RootFileChunk("hello".toByteArray(), 0, 5, true))
        val output = completed(execute(RootTools.FILE_READ, json("scopeId" to "system", "relativePath" to "hosts")))
        assertEquals("hello", output["content"]?.jsonPrimitive?.content)
        assertEquals("system", output["scopeId"]?.jsonPrimitive?.content)
        assertFalse(output.toString().contains("/system/etc"))
        assertEquals(
            RootOperationRequest.FileRead("/system/etc", "/system/etc/hosts", 0, RootTools.DEFAULT_FILE_BYTES),
            port.requests.single(),
        )
    }

    @Test
    fun processPackageAndLogsStayTypedBoundedAndLogsAreRedacted() {
        sessions.start()
        port.next = RootOperationResult.Package(RootPackageRecord("com.example.app", 1234, "/data/app/base.apk", "1"))
        completed(execute(RootTools.PACKAGE_INFO, json("packageName" to "com.example.app")))
        assertTrue(port.requests.last() is RootOperationRequest.PackageInfo)

        port.next = RootOperationResult.Processes(listOf(RootProcessRecord(42, 1000, "system_server")))
        val processes = completed(execute(RootTools.PROCESS_LIST, buildJsonObject {}))
        assertEquals(1, processes["count"]?.jsonPrimitive?.content?.toInt())

        port.next = RootOperationResult.Logs(listOf("token=abc123 Authorization: Bearer secret.value"))
        val logs = completed(execute(RootTools.LOG_READ, buildJsonObject {})).toString()
        assertFalse(logs.contains("abc123"))
        assertFalse(logs.contains("secret.value"))
        assertTrue(logs.contains("redacted"))
    }

    @Test
    fun logLinesAreBoundedBeforeCrossingTheToolOutputBoundary() {
        sessions.start()
        port.next = RootOperationResult.Logs(List(RootTools.MAX_LOG_LINES) { "x".repeat(4_096) })
        val output = completed(execute(RootTools.LOG_READ, buildJsonObject {}))
        val lines = output.getValue("lines").jsonArray
        assertEquals(RootTools.MAX_LOG_LINES, lines.size)
        assertTrue(lines.all { it.jsonPrimitive.content.length <= RootTools.MAX_LOG_LINE_LENGTH })
    }

    @Test
    fun inactiveSessionAndRootServiceFailureNeverBecomeSuccess() {
        assertTrue(execute(RootTools.PROCESS_LIST, buildJsonObject {}) is ToolExecutorResult.Failed)
        sessions.start()
        port.next = RootOperationResult.Failed("ROOT_SERVICE_LOST")
        val failed = execute(RootTools.PROCESS_LIST, buildJsonObject {}) as ToolExecutorResult.Failed
        assertEquals("ROOT_SERVICE_LOST", failed.detail)
        assertTrue(failed.sideEffectFree)
    }

    @Test
    fun structuredBoundaryHasNoGenericCommandOrSecretInput() {
        val fields =
            listOf(
                RootOperationRequest.FileRead::class.java,
                RootOperationRequest.PackageInfo::class.java,
                RootOperationRequest.ProcessList::class.java,
                RootOperationRequest.LogRead::class.java,
            ).flatMap { type -> type.declaredFields.map { field -> field.name } }
        assertFalse(
            fields.any {
                it.contains("command", ignoreCase = true) || it.contains("secret", ignoreCase = true) ||
                    it.contains("token", ignoreCase = true)
            },
        )
    }

    private fun execute(
        name: String,
        args: JsonObject,
    ) = tools.executor(name).execute(
        ExecutableToolCall(
            "call",
            name,
            "1",
            args,
            ExecutionTargetType.LOCAL_ROOT,
            clock.now().plusSeconds(30),
            NoCancellation,
        ),
    )

    private fun completed(result: ToolExecutorResult): JsonObject =
        (result as ToolExecutorResult.Completed).output.jsonObject

    private fun json(vararg pairs: Pair<String, String>) =
        buildJsonObject {
            pairs.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
        }
}

private class FakeOperationPort : RootOperationPort {
    var access = RootAccessStatus(RootGrantState.GRANTED, RootServiceState.CONNECTED)
    var next: RootOperationResult = RootOperationResult.Processes(emptyList())
    val requests = mutableListOf<RootOperationRequest>()

    override fun status() = access

    override fun execute(request: RootOperationRequest): RootOperationResult {
        requests += request
        return next
    }
}
