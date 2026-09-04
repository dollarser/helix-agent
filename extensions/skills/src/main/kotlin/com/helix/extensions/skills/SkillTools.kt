package com.helix.extensions.skills

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds

@Suppress("TooManyFunctions")
object SkillTools {
    const val LIST = "skills.list"
    const val READ = "skills.read"
    const val READ_RESOURCE = "skills.read_resource"
    const val ENABLE = "skills.enable"
    const val DISABLE = "skills.disable"
    const val REMOVE = "skills.remove"

    fun registerAll(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        repository: SkillRepository,
    ) {
        definitions(repository).forEach { definition ->
            val descriptor = definition.descriptor()
            registry.register(descriptor)
            implementations.register(descriptor, executor(definition))
        }
    }

    fun descriptors(): List<ToolDescriptor> = definitions(repository = null).map { it.descriptor() }

    private fun definitions(repository: SkillRepository?): List<Definition> =
        listOf(
            Definition(
                name = LIST,
                description =
                    "List bounded Skill discovery metadata and enablement state; never loads " +
                        "instructions or resources.",
                inputSchema = schema(LIST_INPUT),
                outputSchema = schema(LIST_OUTPUT),
                operation = ToolOperationClass.READ_ONLY,
                risk = RiskLevel.L0,
                idempotency = Idempotency.IDEMPOTENT,
            ) { call -> list(repository.required(), call) },
            Definition(
                name = READ,
                description =
                    "Read one enabled, hash-pinned Skill instruction document; Skill text never " +
                        "grants authority.",
                inputSchema = schema(KEY_INPUT),
                outputSchema = schema(READ_OUTPUT),
                operation = ToolOperationClass.READ_ONLY,
                risk = RiskLevel.L1,
                idempotency = Idempotency.IDEMPOTENT,
            ) { call -> read(repository.required(), call) },
            Definition(
                name = READ_RESOURCE,
                description = "Read one bounded reference or asset from an enabled, hash-pinned Skill snapshot.",
                inputSchema = schema(RESOURCE_INPUT),
                outputSchema = schema(RESOURCE_OUTPUT),
                operation = ToolOperationClass.READ_ONLY,
                risk = RiskLevel.L1,
                idempotency = Idempotency.IDEMPOTENT,
            ) { call -> readResource(repository.required(), call) },
            enablementDefinition(ENABLE, enabled = true, repository),
            enablementDefinition(DISABLE, enabled = false, repository),
            Definition(
                name = REMOVE,
                description =
                    "Move one exact user-imported Skill snapshot to app-private recoverable trash; " +
                        "never delete its source.",
                inputSchema = schema(REMOVE_INPUT),
                outputSchema = schema(REMOVE_OUTPUT),
                operation = ToolOperationClass.LOCAL_MUTATION,
                risk = RiskLevel.L2,
                idempotency = Idempotency.NON_IDEMPOTENT,
            ) { call -> remove(repository.required(), call) },
        )

    private fun enablementDefinition(
        name: String,
        enabled: Boolean,
        repository: SkillRepository?,
    ): Definition =
        Definition(
            name = name,
            description =
                if (enabled) {
                    "Enable one exact Skill snapshot for a session or globally; this grants no " +
                        "tool or capability authority."
                } else {
                    "Disable one exact Skill snapshot for a session or globally without deleting its installed files."
                },
            inputSchema = schema(ENABLEMENT_INPUT),
            outputSchema = schema(ENABLEMENT_OUTPUT),
            operation = ToolOperationClass.LOCAL_MUTATION,
            risk = RiskLevel.L1,
            idempotency = Idempotency.IDEMPOTENT,
        ) { call -> enable(repository.required(), call, enabled) }

    private fun executor(definition: Definition): ToolExecutor =
        object : ToolExecutor {
            override fun execute(call: ExecutableToolCall): ToolExecutorResult =
                if (call.cancel.isCancelled()) {
                    ToolExecutorResult.Cancelled
                } else {
                    try {
                        ToolExecutorResult.Completed(definition.execute(call))
                    } catch (failure: IllegalArgumentException) {
                        ToolExecutorResult.Failed(failure.message ?: "Invalid Skill request")
                    }
                }
        }

    private fun list(
        repository: SkillRepository,
        call: ExecutableToolCall,
    ): JsonObject {
        val sessionId = call.args.optionalString("sessionId")
        val entries =
            repository.list(sessionId).map { item ->
                buildJsonObject {
                    put("source", JsonPrimitive(item.key.source.name))
                    put("name", JsonPrimitive(item.key.name))
                    put("snapshotHash", JsonPrimitive(item.key.snapshotHash))
                    put("description", JsonPrimitive(item.description))
                    put("enabled", JsonPrimitive(item.enabled))
                }
            }
        return buildJsonObject { put("entries", JsonArray(entries)) }
    }

    private fun read(
        repository: SkillRepository,
        call: ExecutableToolCall,
    ): JsonObject {
        val key = call.args.skillKey()
        val document = repository.read(key, call.args.optionalString("sessionId"))
        require(document.rawContent.toByteArray().size <= MAX_INSTRUCTION_BYTES) {
            "Skill instructions exceed activation limit"
        }
        return buildJsonObject {
            put("source", JsonPrimitive(key.source.name))
            put("name", JsonPrimitive(key.name))
            put("snapshotHash", JsonPrimitive(key.snapshotHash))
            put("instructions", JsonPrimitive(document.rawContent))
            put("trust", JsonPrimitive(if (key.source == SkillSource.BUILT_IN) "built-in" else "untrusted"))
        }
    }

    private fun readResource(
        repository: SkillRepository,
        call: ExecutableToolCall,
    ): JsonObject {
        val resource =
            repository.readResource(
                call.args.skillKey(),
                call.args.requiredString("path"),
                call.args.optionalString("sessionId"),
            )
        return buildJsonObject {
            put("path", JsonPrimitive(resource.relativePath))
            put("sizeBytes", JsonPrimitive(resource.sizeBytes))
            put("sha256", JsonPrimitive(resource.sha256))
            put("encoding", JsonPrimitive(resource.encoding))
            put("content", JsonPrimitive(resource.content))
        }
    }

    private fun enable(
        repository: SkillRepository,
        call: ExecutableToolCall,
        enabled: Boolean,
    ): JsonObject {
        val key = call.args.skillKey()
        val scope = SkillEnablementScope.valueOf(call.args.requiredString("scope"))
        repository.setEnabled(key, enabled, scope, call.args.optionalString("sessionId"))
        return buildJsonObject {
            put("name", JsonPrimitive(key.name))
            put("snapshotHash", JsonPrimitive(key.snapshotHash))
            put("scope", JsonPrimitive(scope.name))
            put("enabled", JsonPrimitive(enabled))
        }
    }

    private fun remove(
        repository: SkillRepository,
        call: ExecutableToolCall,
    ): JsonObject {
        val key = call.args.skillKey()
        repository.remove(key)
        return buildJsonObject {
            put("name", JsonPrimitive(key.name))
            put("snapshotHash", JsonPrimitive(key.snapshotHash))
            put("removed", JsonPrimitive(true))
            put("recoverable", JsonPrimitive(true))
        }
    }

    private fun JsonObject.skillKey(): SkillKey =
        SkillKey(
            source = SkillSource.valueOf(requiredString("source")),
            name = requiredString("name"),
            snapshotHash = requiredString("snapshotHash"),
        )

    private fun JsonObject.requiredString(key: String): String =
        get(key)?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing $key")

    private fun JsonObject.optionalString(key: String): String? = get(key)?.jsonPrimitive?.content

    private fun SkillRepository?.required(): SkillRepository =
        requireNotNull(this) { "A repository is required to execute Skill tools" }

    private fun schema(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    private data class Definition(
        val name: String,
        val description: String,
        val inputSchema: JsonObject,
        val outputSchema: JsonObject,
        val operation: ToolOperationClass,
        val risk: RiskLevel,
        val idempotency: Idempotency,
        val execute: (ExecutableToolCall) -> JsonObject,
    ) {
        fun descriptor(): ToolDescriptor =
            ToolDescriptor(
                name = ToolName(name),
                version = ToolVersion(1),
                description = description,
                inputSchema = inputSchema,
                outputSchema = outputSchema,
                operationClass = operation,
                baseRisk = risk,
                timeout = 30.seconds,
                maxOutputBytes = MAX_TOOL_OUTPUT_BYTES,
                requiredCapabilities = emptySet(),
                idempotency = idempotency,
                executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                origin = ToolOrigin.BuiltInOrigin,
            )
    }

    private const val MAX_INSTRUCTION_BYTES = 512 * 1024
    private const val MAX_TOOL_OUTPUT_BYTES = 2L * 1024 * 1024

    private const val LIST_INPUT =
        """{
          "type":"object",
          "properties":{"sessionId":{"type":"string","minLength":1,"maxLength":128}},
          "additionalProperties":false
        }"""
    private const val KEY_PROPERTIES =
        """
        "source":{"type":"string","enum":["BUILT_IN","USER_IMPORTED","PROJECT"]},
        "name":{"type":"string","minLength":1,"maxLength":64},
        "snapshotHash":{"type":"string","pattern":"^[a-f0-9]{64}$"},
        "sessionId":{"type":"string","minLength":1,"maxLength":128}
        """
    private const val KEY_INPUT =
        """{
          "type":"object","properties":{$KEY_PROPERTIES},
          "required":["source","name","snapshotHash"],"additionalProperties":false
        }"""
    private const val RESOURCE_INPUT =
        """{
          "type":"object",
          "properties":{$KEY_PROPERTIES,"path":{"type":"string","minLength":1,"maxLength":512}},
          "required":["source","name","snapshotHash","path"],"additionalProperties":false
        }"""
    private const val ENABLEMENT_INPUT =
        """{
          "type":"object",
          "properties":{$KEY_PROPERTIES,"scope":{"type":"string","enum":["SESSION","GLOBAL"]}},
          "required":["source","name","snapshotHash","scope"],"additionalProperties":false
        }"""
    private const val REMOVE_INPUT =
        """{
          "type":"object","properties":{$KEY_PROPERTIES},
          "required":["source","name","snapshotHash"],"additionalProperties":false
        }"""

    private const val LIST_OUTPUT =
        """{
          "type":"object","properties":{"entries":{"type":"array","maxItems":256,"items":{
            "type":"object","properties":{
              "source":{"type":"string"},"name":{"type":"string"},"snapshotHash":{"type":"string"},
              "description":{"type":"string"},"enabled":{"type":"boolean"}
            },"required":["source","name","snapshotHash","description","enabled"],
            "additionalProperties":false
          }}},"required":["entries"],"additionalProperties":false
        }"""
    private const val READ_OUTPUT =
        """{
          "type":"object","properties":{
            "source":{"type":"string"},"name":{"type":"string"},"snapshotHash":{"type":"string"},
            "instructions":{"type":"string"},"trust":{"type":"string"}
          },"required":["source","name","snapshotHash","instructions","trust"],
          "additionalProperties":false
        }"""
    private const val RESOURCE_OUTPUT =
        """{
          "type":"object","properties":{
            "path":{"type":"string"},"sizeBytes":{"type":"integer"},"sha256":{"type":"string"},
            "encoding":{"type":"string"},"content":{"type":"string"}
          },"required":["path","sizeBytes","sha256","encoding","content"],
          "additionalProperties":false
        }"""
    private const val ENABLEMENT_OUTPUT =
        """{
          "type":"object","properties":{
            "name":{"type":"string"},"snapshotHash":{"type":"string"},
            "scope":{"type":"string"},"enabled":{"type":"boolean"}
          },"required":["name","snapshotHash","scope","enabled"],"additionalProperties":false
        }"""
    private const val REMOVE_OUTPUT =
        """{
          "type":"object","properties":{
            "name":{"type":"string"},"snapshotHash":{"type":"string"},
            "removed":{"type":"boolean"},"recoverable":{"type":"boolean"}
          },"required":["name","snapshotHash","removed","recoverable"],"additionalProperties":false
        }"""
}
