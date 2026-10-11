package com.helix.extensions.chatreply

import android.content.Context
import com.helix.core.model.Capability
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.extensions.plugin.HelixPlugin
import com.helix.extensions.plugin.PluginBundledSkill
import com.helix.extensions.plugin.PluginManifest
import com.helix.extensions.plugin.PluginManifestReader
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolBinding
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Duration.Companion.seconds

/** Chat-specific guidance as a separate native plugin; device access remains owned by Mobile Use. */
class ChatReplyPlugin(
    context: Context,
) : HelixPlugin {
    override val manifest: PluginManifest =
        context.assets.open(MANIFEST_ASSET).use { PluginManifestReader.parse(it.readBytes()) }

    override val dataOrigin = com.helix.core.policy.DataOrigin.ACCESSIBILITY

    override val preferredTools =
        setOf("ui.snapshot", "ui.find", "ui.set_text", "ui.wait")

    override val bundledSkills =
        listOf(
            PluginBundledSkill(
                name = "chat-reply",
                description = "Interpret a visible chat and draft replies for user review.",
                content =
                    context.assets
                        .open(SKILL_ASSET)
                        .bufferedReader()
                        .use { it.readText() },
            ),
        )

    private val origin =
        ToolOrigin.PluginOrigin(
            PLUGIN_ID,
            manifest.version,
            RUNTIME_ID,
        )

    private val descriptor =
        ToolDescriptor(
            name = ToolName(TOOL_NAME),
            version = ToolVersion(1),
            description =
                "Start an explicit user-requested analysis of the currently visible authorized chat. " +
                    "This does not read the screen, call a model, store history, or send messages; " +
                    "follow the chat-reply skill with existing ui.snapshot tools.",
            inputSchema =
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", buildJsonObject {})
                    put("required", kotlinx.serialization.json.JsonArray(emptyList()))
                    put("additionalProperties", JsonPrimitive(false))
                },
            outputSchema =
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "status",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("enum", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("READY"))))
                                },
                            )
                            put(
                                "next",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                },
                            )
                        },
                    )
                    put(
                        "required",
                        kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("status"), JsonPrimitive("next"))),
                    )
                    put("additionalProperties", JsonPrimitive(false))
                },
            operationClass = ToolOperationClass.READ_ONLY,
            timeout = 5.seconds,
            maxOutputBytes = 1_024,
            requiredCapabilities = setOf(Capability.MOBILE_USE),
            idempotency = Idempotency.IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = origin,
        )

    override fun tools(): List<ToolBinding> =
        listOf(
            ToolBinding(
                descriptor,
                object : ToolExecutor {
                    override fun execute(call: ExecutableToolCall): ToolExecutorResult =
                        if (call.cancel.isCancelled()) {
                            ToolExecutorResult.Cancelled
                        } else {
                            ToolExecutorResult.Completed(
                                buildJsonObject {
                                    put("status", JsonPrimitive("READY"))
                                    put("next", JsonPrimitive("Observe the authorized visible chat with ui.snapshot."))
                                },
                            )
                        }
                },
            ),
        )

    init {
        require(manifest.name == PLUGIN_ID) { "unexpected Chat Reply plugin id" }
        require(manifest.helixRuntimeId == RUNTIME_ID) { "Chat Reply manifest has no runtime binding" }
    }

    companion object {
        const val PLUGIN_ID = "chat-reply"
        const val RUNTIME_ID = "chat-reply"
        const val TOOL_NAME = "chat.assist"
        private const val MANIFEST_ASSET = "plugins/chat-reply/plugin.json"
        private const val SKILL_ASSET = "plugins/chat-reply/skills/chat-reply/SKILL.md"
    }
}
