package com.helix.extensions.skills

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolRegistry
import com.helix.tools.framework.ToolSchemaValidation
import com.helix.tools.framework.ToolSchemaValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

class SkillToolsTest {
    @Test
    fun `tool contracts preserve read mutation and approval boundaries`() {
        val byName = SkillTools.descriptors().associateBy { it.name.value }

        assertEquals(
            setOf(
                SkillTools.LIST,
                SkillTools.READ,
                SkillTools.READ_RESOURCE,
                SkillTools.ENABLE,
                SkillTools.DISABLE,
                SkillTools.REMOVE,
            ),
            byName.keys,
        )
        assertEquals(RiskLevel.L0, byName.getValue(SkillTools.LIST).baseRisk)
        assertEquals(RiskLevel.L1, byName.getValue(SkillTools.READ).baseRisk)
        assertEquals(RiskLevel.L1, byName.getValue(SkillTools.READ_RESOURCE).baseRisk)
        assertEquals(RiskLevel.L1, byName.getValue(SkillTools.ENABLE).baseRisk)
        assertEquals(RiskLevel.L2, byName.getValue(SkillTools.REMOVE).baseRisk)
        assertEquals(ToolOperationClass.READ_ONLY, byName.getValue(SkillTools.READ).operationClass)
        assertEquals(ToolOperationClass.LOCAL_MUTATION, byName.getValue(SkillTools.REMOVE).operationClass)
        assertTrue(byName.values.all { it.requiredCapabilities.isEmpty() })
    }

    @Test
    fun `registered tools list enable and read through exact schema-validated contracts`() {
        val root = Files.createTempDirectory("skill-tools")
        val repository =
            SkillRepository(
                root.resolve("snapshots"),
                root.resolve("state/enablement.txt"),
                root.resolve("trash"),
            )
        val registry = ToolRegistry()
        val implementations = ToolImplementationRegistry()
        SkillTools.registerAll(registry, implementations, repository)

        val listOutput = execute(registry, implementations, SkillTools.LIST, "{}")
        val entries = listOutput["entries"]!!.jsonArray
        assertEquals(5, entries.size)
        assertEquals("5", listOutput.getValue("nextOffset").jsonPrimitive.content)
        assertEquals("true", listOutput.getValue("eof").jsonPrimitive.content)
        assertTrue(entries.all { it.jsonObject["enabled"]!!.jsonPrimitive.content == "true" })
        val selected = entries.first().jsonObject
        val source = selected.getValue("source").jsonPrimitive.content
        val name = selected.getValue("name").jsonPrimitive.content
        val hash = selected.getValue("snapshotHash").jsonPrimitive.content

        val disableArgs = keyArgs(source, name, hash, extra = "\"scope\":\"SESSION\",\"sessionId\":\"s1\"")
        execute(registry, implementations, SkillTools.DISABLE, disableArgs)
        val failedRead =
            executeRaw(registry, implementations, SkillTools.READ, keyArgs(source, name, hash, "\"sessionId\":\"s1\""))
        assertTrue(failedRead is ToolExecutorResult.Failed)

        val read = execute(registry, implementations, SkillTools.READ, keyArgs(source, name, hash))
        assertEquals("built-in", read.getValue("trust").jsonPrimitive.content)
        val instructions = read.getValue("instructions").jsonPrimitive.content
        assertTrue(instructions.startsWith("---\n"))
        assertTrue(instructions.contains("name: $name"))

        // Skill content and allowed-tools never register an executor or expand the tool table.
        assertEquals(6, registry.all().size)
        assertFalse(registry.all().any { it.name.value == "bash" || it.name.value == "root" })
    }

    @Test
    fun `list output is bounded and exposes a deterministic continuation offset`() {
        val root = Files.createTempDirectory("skill-tools-page")
        val repository =
            SkillRepository(
                root.resolve("snapshots"),
                root.resolve("state/enablement.txt"),
                root.resolve("trash"),
            )
        val registry = ToolRegistry()
        val implementations = ToolImplementationRegistry()
        SkillTools.registerAll(registry, implementations, repository)

        val first = execute(registry, implementations, SkillTools.LIST, "{\"limit\":2}")
        assertEquals(2, first.getValue("entries").jsonArray.size)
        assertEquals("2", first.getValue("nextOffset").jsonPrimitive.content)
        assertEquals("false", first.getValue("eof").jsonPrimitive.content)

        val second = execute(registry, implementations, SkillTools.LIST, "{\"offset\":2,\"limit\":256}")
        assertEquals(3, second.getValue("entries").jsonArray.size)
        assertEquals("5", second.getValue("nextOffset").jsonPrimitive.content)
        assertEquals("true", second.getValue("eof").jsonPrimitive.content)
    }

    private fun execute(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        name: String,
        args: String,
    ): JsonObject {
        val result = executeRaw(registry, implementations, name, args) as ToolExecutorResult.Completed
        val output = result.output
        val descriptor = registry.resolve(ToolName(name), ToolVersion(1))
        assertEquals(ToolSchemaValidation.Valid, ToolSchemaValidator.validate(descriptor.outputSchema, output))
        return output.jsonObject
    }

    private fun executeRaw(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        name: String,
        args: String,
    ): ToolExecutorResult {
        val descriptor = registry.resolve(ToolName(name), ToolVersion(1))
        val arguments = Json.parseToJsonElement(args).jsonObject
        assertEquals(ToolSchemaValidation.Valid, ToolSchemaValidator.validate(descriptor.inputSchema, arguments))
        return implementations.resolve(descriptor.name, descriptor.version).execute(
            ExecutableToolCall(
                toolCallId = "call-$name",
                toolName = name,
                toolVersion = "1",
                args = arguments,
                executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                deadline = Instant.parse("2030-01-01T00:00:00Z"),
                cancel = NoCancellation,
            ),
        )
    }

    private fun keyArgs(
        source: String,
        name: String,
        hash: String,
        extra: String? = null,
    ): String =
        buildString {
            append("{\"source\":\"").append(source)
            append("\",\"name\":\"").append(name)
            append("\",\"snapshotHash\":\"").append(hash).append('"')
            if (extra != null) append(',').append(extra)
            append('}')
        }
}
