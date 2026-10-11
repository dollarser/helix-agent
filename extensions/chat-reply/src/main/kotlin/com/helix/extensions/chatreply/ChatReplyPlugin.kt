package com.helix.extensions.chatreply

import android.content.Context
import com.helix.extensions.plugin.HelixPlugin
import com.helix.extensions.plugin.PluginManifest
import com.helix.extensions.plugin.PluginManifestReader
import com.helix.extensions.skills.SkillLoader
import com.helix.tools.framework.ToolBinding

/** A pure workflow contribution. Phone observation and effects stay with authorized Mobile Use. */
class ChatReplyPlugin(
    context: Context,
) : HelixPlugin {
    override val manifest: PluginManifest =
        context.assets.open(MANIFEST_ASSET).use { PluginManifestReader.parse(it.readBytes()) }

    override val preferredTools = setOf("ui.snapshot", "ui.find", "ui.set_text", "ui.wait")

    override val skills =
        listOf(
            SkillLoader().loadBuiltIn(
                context.assets
                    .open(SKILL_ASSET)
                    .bufferedReader()
                    .use { it.readText() },
                "chat-reply",
            ),
        )

    override fun tools(): List<ToolBinding> = emptyList()

    init {
        require(manifest.name == PLUGIN_ID) { "unexpected Chat Reply plugin id" }
        require(manifest.helixRuntimeId == RUNTIME_ID) { "Chat Reply manifest has no runtime binding" }
    }

    companion object {
        const val PLUGIN_ID = "chat-reply"
        const val RUNTIME_ID = "chat-reply"
        private const val MANIFEST_ASSET = "plugins/chat-reply/plugin.json"
        private const val SKILL_ASSET = "plugins/chat-reply/skills/chat-reply/SKILL.md"
    }
}
