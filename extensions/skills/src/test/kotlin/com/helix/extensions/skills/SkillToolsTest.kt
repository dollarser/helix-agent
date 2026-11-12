package com.helix.extensions.skills

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.NoCancellation
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutorResult
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
import java.nio.file.Path
import java.time.Instant

class SkillToolsTest {
    @Test
    fun `tool contracts preserve read mutation and approval boundaries`() {
        val byName = SkillTools.descriptors().associateBy { it.name.value }

        assertEquals(
            setOf(
                SkillTools.SCRIPTS,
                SkillTools.LIST,
                SkillTools.READ,
                SkillTools.READ_RESOURCE,
                SkillTools.ENABLE,
                SkillTools.DISABLE,
                SkillTools.REMOVE,
            ),
            byName.keys,
        )
        assertEquals(ToolOperationClass.READ_ONLY, byName.getValue(SkillTools.LIST).operationClass)
        assertEquals(ToolOperationClass.READ_ONLY, byName.getValue(SkillTools.READ).operationClass)
        assertEquals(ToolOperationClass.READ_ONLY, byName.getValue(SkillTools.READ_RESOURCE).operationClass)
        assertEquals(ToolOperationClass.LOCAL_MUTATION, byName.getValue(SkillTools.ENABLE).operationClass)
        assertEquals(ToolOperationClass.LOCAL_MUTATION, byName.getValue(SkillTools.REMOVE).operationClass)
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

        SkillTools.registerAll(registry, repository)

        val listOutput = execute(registry, SkillTools.LIST, "{}")
        val entries = listOutput["entries"]!!.jsonArray
        assertEquals(9, entries.size)
        assertEquals("9", listOutput.getValue("nextOffset").jsonPrimitive.content)
        assertEquals("true", listOutput.getValue("eof").jsonPrimitive.content)
        assertTrue(entries.all { it.jsonObject["enabled"]!!.jsonPrimitive.content == "true" })
        val selected = entries.first().jsonObject
        val source = selected.getValue("source").jsonPrimitive.content
        val name = selected.getValue("name").jsonPrimitive.content
        val hash = selected.getValue("snapshotHash").jsonPrimitive.content

        val disableArgs = keyArgs(source, name, hash, extra = "\"scope\":\"SESSION\",\"sessionId\":\"s1\"")
        execute(registry, SkillTools.DISABLE, disableArgs, "s1")
        val failedRead =
            executeRaw(
                registry,
                SkillTools.READ,
                keyArgs(source, name, hash, "\"sessionId\":\"s2\""),
                "s1",
            )
        assertTrue(failedRead is ToolExecutorResult.Failed)

        val read = execute(registry, SkillTools.READ, keyArgs(source, name, hash))
        assertEquals("built-in", read.getValue("trust").jsonPrimitive.content)
        val instructions = read.getValue("instructions").jsonPrimitive.content
        assertTrue(instructions.startsWith("---\n"))
        assertTrue(instructions.contains("name: $name"))

        // Skill content and allowed-tools never register an executor or expand the tool table.
        assertEquals(7, registry.all().size)
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

        SkillTools.registerAll(registry, repository)

        val first = execute(registry, SkillTools.LIST, "{\"limit\":2}")
        assertEquals(2, first.getValue("entries").jsonArray.size)
        assertEquals("2", first.getValue("nextOffset").jsonPrimitive.content)
        assertEquals("false", first.getValue("eof").jsonPrimitive.content)

        val second = execute(registry, SkillTools.LIST, "{\"offset\":2,\"limit\":256}")
        assertEquals(7, second.getValue("entries").jsonArray.size)
        assertEquals("9", second.getValue("nextOffset").jsonPrimitive.content)
        assertEquals("true", second.getValue("eof").jsonPrimitive.content)
    }

    @Test
    fun `read-only skill calls omit session hints and reject empty hints`() {
        val key = keyArgs("BUILT_IN", "android-ui-task", "0".repeat(64))
        val arguments =
            mapOf(
                SkillTools.LIST to "{}",
                SkillTools.READ to key,
                SkillTools.READ_RESOURCE to
                    keyArgs("BUILT_IN", "android-ui-task", "0".repeat(64), "\"path\":\"references/context.md\""),
            )
        arguments.forEach { (name, raw) ->
            val descriptor = SkillTools.descriptors().single { it.name.value == name }
            val valid = Json.parseToJsonElement(raw).jsonObject
            assertEquals(ToolSchemaValidation.Valid, ToolSchemaValidator.validate(descriptor.inputSchema, valid))
            val malformed = JsonObject(valid + ("sessionId" to kotlinx.serialization.json.JsonPrimitive("")))
            assertFalse(ToolSchemaValidator.validate(descriptor.inputSchema, malformed) == ToolSchemaValidation.Valid)
        }
    }

    @Test
    fun `list keeps identical plugin and independent installation identities`() {
        val root = Files.createTempDirectory("skill-tools-owners")
        var selected = true
        val repository =
            SkillRepository(
                root.resolve("snapshots"),
                root.resolve("state"),
                root.resolve("trash"),
                builtIns = emptyList(),
                pluginAvailable = { key, session -> key.ownerId == "plugin-a" && selected && session == "owner" },
            )
        val independent = installSnapshot(root, repository)
        val importer = SkillImportService(root.resolve("staging"))
        val plugin =
            repository.registerSnapshot(
                importer.commit(
                    importer.stageDirectory(root.resolve("source/fixture")),
                    root.resolve("snapshots"),
                    "plugin-a",
                ),
            )
        val registry = ToolRegistry()
        SkillTools.registerAll(registry, repository)
        assertOwnerEntries(registry)
        val args = keyArgs(plugin.source.name, plugin.name, plugin.snapshotHash, "\"ownerId\":\"plugin-a\"")
        assertTrue(
            execute(
                registry,
                SkillTools.READ,
                args,
                "owner",
            ).getValue("instructions").jsonPrimitive.content.contains("Fixture"),
        )
        assertTrue(
            executeRaw(
                registry,
                SkillTools.READ,
                keyArgs(independent.source.name, independent.name, independent.snapshotHash),
                "owner",
            ) is ToolExecutorResult.Failed,
        )
        selected = false
        assertTrue(executeRaw(registry, SkillTools.READ, args, "owner") is ToolExecutorResult.Failed)
    }

    @Test
    fun `remove independent snapshot moves only its files to recoverable trash`() {
        val root = Files.createTempDirectory("skill-tools-remove")
        val repository =
            SkillRepository(
                root.resolve("snapshots"),
                root.resolve("state"),
                root.resolve("trash"),
                builtIns = emptyList(),
            )
        val key = installSnapshot(root, repository)
        val registry = ToolRegistry()
        SkillTools.registerAll(registry, repository)
        val args = keyArgs(key.source.name, key.name, key.snapshotHash)
        val output = execute(registry, SkillTools.REMOVE, args)
        assertEquals("true", output.getValue("removed").jsonPrimitive.content)
        assertEquals("true", output.getValue("recoverable").jsonPrimitive.content)
        assertFalse(output.containsKey("retainedByPlugin"))
        assertFalse(repository.hasSnapshot(key))
        Files.list(root.resolve("trash")).use { assertEquals(1L, it.count()) }
        assertTrue(executeRaw(registry, SkillTools.REMOVE, args) is ToolExecutorResult.Failed)
    }

    private fun assertOwnerEntries(registry: ToolRegistry) {
        val entries =
            execute(
                registry,
                SkillTools.LIST,
                "{}",
                "owner",
            ).getValue("entries").jsonArray.map { it.jsonObject }
        assertEquals(2, entries.size)
        val own = entries.single { it.getValue("ownerId").jsonPrimitive.content == "plugin-a" }
        val standalone =
            entries.single {
                it
                    .getValue("ownerId")
                    .jsonPrimitive.content
                    .isEmpty()
            }
        assertEquals("true", own.getValue("enabled").jsonPrimitive.content)
        assertEquals("false", own.getValue("independentlySelectable").jsonPrimitive.content)
        assertEquals("false", standalone.getValue("enabled").jsonPrimitive.content)
        assertEquals("true", standalone.getValue("independentlySelectable").jsonPrimitive.content)
    }

    private fun installSnapshot(
        root: Path,
        repository: SkillRepository,
    ): SkillKey {
        val directory = Files.createDirectories(root.resolve("source/fixture"))
        Files.writeString(directory.resolve("SKILL.md"), "---\nname: fixture\ndescription: Fixture\n---\nFixture body")
        val importer = SkillImportService(root.resolve("staging"))
        val staged = importer.stageDirectory(directory)
        return repository.registerSnapshot(importer.commit(staged, root.resolve("snapshots")))
    }

    private fun execute(
        registry: ToolRegistry,
        name: String,
        args: String,
        sessionId: String? = null,
    ): JsonObject {
        val result = executeRaw(registry, name, args, sessionId) as ToolExecutorResult.Completed
        val output = result.output
        val descriptor = registry.resolve(ToolName(name), ToolVersion(1))
        assertEquals(ToolSchemaValidation.Valid, ToolSchemaValidator.validate(descriptor.outputSchema, output))
        return output.jsonObject
    }

    private fun executeRaw(
        registry: ToolRegistry,
        name: String,
        args: String,
        sessionId: String? = null,
    ): ToolExecutorResult {
        val descriptor = registry.resolve(ToolName(name), ToolVersion(1))
        val arguments = Json.parseToJsonElement(args).jsonObject
        assertEquals(ToolSchemaValidation.Valid, ToolSchemaValidator.validate(descriptor.inputSchema, arguments))
        return registry.executor(descriptor.name, descriptor.version).execute(
            ExecutableToolCall(
                toolCallId = "call-$name",
                toolName = name,
                toolVersion = "1",
                args = arguments,
                executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                deadline = Instant.parse("2030-01-01T00:00:00Z"),
                cancel = NoCancellation,
                sessionId = sessionId,
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
            if (extra?.contains("\"ownerId\"") != true) append(",\"ownerId\":\"\"")
            if (extra != null) append(',').append(extra)
            append('}')
        }
}
