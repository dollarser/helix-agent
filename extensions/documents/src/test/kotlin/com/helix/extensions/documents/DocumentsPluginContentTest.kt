package com.helix.extensions.documents

import com.helix.extensions.plugin.HelixPlugin
import com.helix.extensions.plugin.NativePluginCatalog
import com.helix.extensions.plugin.NativePluginState
import com.helix.extensions.plugin.PluginManifestReader
import com.helix.extensions.plugin.PluginRegistry
import com.helix.extensions.plugin.executionContext
import com.helix.extensions.skills.SkillKey
import com.helix.tools.framework.ToolImagePublication
import com.helix.tools.framework.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class DocumentsPluginContentTest {
    private val assets = File("src/main/assets/plugins/documents")
    private val manifest get() = PluginManifestReader.parse(File(assets, "plugin.json").readBytes())
    private val skill get() = DocumentsPlugin.readSkill(File(assets, "skills/document-reading/SKILL.md").readText())

    @Test fun shippedManifestAndReadingSkillMatchTheActualRuntimeLoader() {
        assertEquals(DocumentTools.origin.pluginId, manifest.name)
        assertEquals(DocumentTools.origin.pluginVersion, manifest.version)
        assertEquals(DocumentTools.origin.runtimeId, manifest.helixRuntimeId)
        assertEquals(DocumentsPlugin.SKILL_NAME, skill.catalogEntry.name)
        assertFalse(skill.body.isBlank())
        assertThrows(IllegalArgumentException::class.java) {
            com.helix.extensions.skills
                .SkillLoader()
                .loadBuiltIn(skill.rawContent, "documents")
        }
    }

    @Test fun selectionAndDisableGuardTheShippedToolsAndSkillTogether() {
        val catalog = Catalog()
        val tools = ToolRegistry()
        val registry = PluginRegistry(tools, catalog)
        val actualManifest = manifest
        val actualSkill = skill
        val snapshots =
            object : DocumentSnapshots {
                override fun create(
                    call: com.helix.tools.framework.ExecutableToolCall,
                    path: String,
                ): DocumentSnapshot = error("Not executed")

                override fun resolve(
                    call: com.helix.tools.framework.ExecutableToolCall,
                    reference: String,
                ): DocumentSnapshot = error("Not executed")
            }
        registry.register(
            object : HelixPlugin {
                override val manifest = actualManifest
                override val skills = listOf(actualSkill)

                override fun tools() =
                    DocumentTools.bindings(
                        snapshots,
                        DocumentParser { _, _, _ -> error("Not executed") },
                        ToolImagePublication { _, _, _ -> error("Not executed") },
                    )
            },
        )
        val key = actualSkill.catalogEntry.let { SkillKey(it.source, it.name, it.contentHash) }
        val descriptor = DocumentTools.descriptor("read")
        assertNull(registry.executionContext(descriptor, "s"))
        assertFalse(registry.skillAvailable(key, "s"))
        catalog.selected = true
        assertNotNull(registry.executionContext(descriptor, "s"))
        assertEquals(1, registry.skillDocuments("s").size)
        assertNull(registry.executionContext(descriptor, "other"))
        registry.setEnabled("documents", false)
        assertNull(registry.executionContext(descriptor, "s"))
        assertFalse(registry.skillAvailable(key, "s"))
        assertEquals(0, tools.snapshot().size)
    }

    private class Catalog : NativePluginCatalog {
        override val mutationLock = Any()
        var selected = false
        private var state: NativePluginState? = null

        override fun selectionId(
            sessionId: String,
            pluginId: String,
        ) = if (selected && sessionId == "s" &&
            pluginId == "documents"
        ) {
            "selection"
        } else {
            null
        }

        override fun find(pluginId: String) = state?.takeIf { it.pluginId == pluginId }

        override fun publish(
            state: NativePluginState,
            expectedRevision: Long?,
        ) {
            check(this.state?.revision == expectedRevision)
            this.state = state
        }
    }
}
