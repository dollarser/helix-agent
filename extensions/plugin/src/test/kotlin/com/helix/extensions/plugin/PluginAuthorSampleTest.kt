package com.helix.extensions.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginAuthorSampleTest {
    @Test fun browserNativeExampleDoesNotCreateAHostOrGrantWebsiteAccess() {
        val bytes =
            requireNotNull(javaClass.getResourceAsStream("/samples/known-native/browser-plugin.json"))
                .use { it.readBytes() }
        assertEquals("browser", PluginManifestReader.parse(bytes).helixRuntimeId)
        val bundle = PluginPackageReader().parse(mapOf("plugin.json" to bytes))
        assertTrue(bundle.componentDiagnostics.any { it.kind == PluginComponentKind.NATIVE && it.omitted })
        assertTrue(bundle.endpoints.isEmpty())
        assertTrue(bundle.skills.isEmpty())
    }

    @Test fun remoteMcpExampleIsRecognizedButNotConnectedByParsing() {
        val files =
            listOf("plugin.json", "mcp.json").associateWith {
                requireNotNull(javaClass.getResourceAsStream("/samples/remote-mcp/$it")).use { stream ->
                    stream.readBytes()
                }
            }
        val bundle = PluginPackageReader().parse(files)
        assertEquals("https://example.invalid/mcp", bundle.endpoints.single().url)
        assertEquals(PluginRepairAction.CONNECT, bundle.componentDiagnostics.single().action)
        assertTrue(bundle.skills.isEmpty())
    }

    @Test fun knownNativeManifestNeverCreatesAFactoryThroughPortableImport() {
        val bytes =
            requireNotNull(javaClass.getResourceAsStream("/samples/known-native/plugin.json"))
                .use { it.readBytes() }
        assertEquals("chat-reply", PluginManifestReader.parse(bytes).helixRuntimeId)
        val bundle = PluginPackageReader().parse(mapOf("plugin.json" to bytes))
        assertTrue(bundle.componentDiagnostics.any { it.kind == PluginComponentKind.NATIVE && it.omitted })
        assertTrue(bundle.endpoints.isEmpty())
        assertTrue(bundle.skills.isEmpty())
    }

    @Test fun pureWorkflowExampleUsesTheProductionParserWithoutPlaceholderTools() {
        val paths = listOf("plugin.json", "skills/pure-workflow/SKILL.md")
        val files =
            paths.associateWith { path ->
                requireNotNull(javaClass.getResourceAsStream("/samples/pure-workflow/$path")).use { it.readBytes() }
            }
        val bundle = PluginPackageReader().parse(files)
        assertEquals("pure-workflow", bundle.name)
        assertTrue(bundle.endpoints.isEmpty())
        assertEquals(1, bundle.skills.size)
        assertTrue(bundle.diagnostics.isEmpty())
    }
}
