package com.helix.extensions.documents

import android.content.Context
import com.helix.extensions.plugin.HelixPlugin
import com.helix.extensions.plugin.PluginManifestReader
import com.helix.extensions.skills.SkillLoader
import com.helix.tools.framework.ToolImagePublication

class DocumentsPlugin(
    context: Context,
    snapshots: DocumentSnapshots,
    parser: DocumentParser,
    images: ToolImagePublication,
) : HelixPlugin {
    override val manifest =
        context.assets.open("plugins/documents/plugin.json").use {
            PluginManifestReader.parse(it.readBytes())
        }
    override val skills =
        listOf(
            readSkill(
                context.assets
                    .open(
                        "plugins/documents/skills/document-reading/SKILL.md",
                    ).bufferedReader()
                    .use {
                        it.readText()
                    },
            ),
        )
    override val requiredInstructions =
        context.assets
            .open("plugins/documents/required.md")
            .bufferedReader()
            .use { it.readText() }
    override val preferredTools = DocumentTools.names
    private val bindings = DocumentTools.bindings(snapshots, parser, images)

    init {
        require(manifest.name == DocumentTools.origin.pluginId)
        require(
            manifest.version == DocumentTools.origin.pluginVersion &&
                manifest.helixRuntimeId == DocumentTools.origin.runtimeId,
        )
    }

    override fun tools() = bindings

    override fun runtimeTarget() =
        "PDF / DOCX local reading. No extra backend. DOCX has no page layout, macro execution or external downloads."

    companion object {
        const val SKILL_NAME = "document-reading"

        internal fun readSkill(content: String) = SkillLoader().loadBuiltIn(content, SKILL_NAME)
    }
}
