package com.helix.extensions.browser

import android.content.Context
import com.helix.core.policy.DataOrigin
import com.helix.extensions.plugin.HelixPlugin
import com.helix.extensions.plugin.PluginManifestReader
import com.helix.extensions.skills.SkillLoader
import com.helix.tools.browser.BrowserToolBridge
import com.helix.tools.browser.BrowserTools
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolVisualPreparation

/** Capability contribution only. The existing Activity owner retains WebViews and manual tabs. */
class BrowserPlugin(
    context: Context,
    bridge: BrowserToolBridge,
    visualPreparation: ToolVisualPreparation,
) : HelixPlugin {
    override val manifest =
        context.assets.open("plugins/browser/plugin.json").use { PluginManifestReader.parse(it.readBytes()) }

    override val skills =
        listOf(
            SkillLoader().loadBuiltIn(
                context.assets
                    .open("plugins/browser/skills/browser/SKILL.md")
                    .bufferedReader()
                    .use { it.readText() },
                PLUGIN_ID,
                mapOf(
                    "references/browser-workflow.md" to
                        context.assets
                            .open("plugins/browser/skills/browser/references/browser-workflow.md")
                            .bufferedReader()
                            .use { it.readText() },
                ),
            ),
        )
    override val requiredInstructions =
        context.assets
            .open("plugins/browser/required.md")
            .bufferedReader()
            .use { it.readText() }
    override val dataOrigin = DataOrigin.BROWSER
    override val preferredTools = setOf("browser.open", "browser.snapshot", "browser.find")
    private val bindings =
        BrowserTools.bindings(
            bridge,
            ToolOrigin.PluginOrigin(manifest.name, manifest.version, RUNTIME_ID),
            visualPreparation,
        )

    init {
        require(manifest.name == PLUGIN_ID && manifest.helixRuntimeId == RUNTIME_ID)
    }

    override fun runtimeTarget() = "Helix in-app browser"

    override fun tools() = bindings

    companion object {
        const val PLUGIN_ID = "browser"
        const val RUNTIME_ID = "browser"
    }
}
