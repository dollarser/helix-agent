package com.helix.extensions.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginComponentDiagnosticsTest {
    private val reader = PluginPackageReader()
    private val skill = "---\nname: guide\ndescription: A bounded workflow\n---\nRead supplied text".toByteArray()
    private val standard =
        """{
        "${'$'}schema":"https://agent-plugins.org/schemas/1.0.0/plugin.schema.json","name":"sample"
    }""".toByteArray()

    private fun foreign(
        extra: String = "",
        files: Map<String, ByteArray> = emptyMap(),
    ) = reader.parse(
        mapOf(
            ".claude-plugin/plugin.json" to "{\"name\":\"sample\"$extra}".toByteArray(),
            "skills/guide/SKILL.md" to skill,
        ) + files,
    )

    @Test fun unsupportedExecutionComponentsAreReportedInBothEntrypoints() {
        val kinds =
            mapOf(
                "hooks" to PluginComponentKind.HOOK,
                "agents" to PluginComponentKind.AGENT,
                "commands" to PluginComponentKind.COMMAND,
                "lspServers" to PluginComponentKind.LSP,
            )
        for ((field, kind) in kinds) {
            val files = mapOf("$field/definition.json" to "{}".toByteArray())
            val packages =
                listOf(
                    foreign(files = files),
                    reader.parse(mapOf("plugin.json" to standard, "skills/guide/SKILL.md" to skill) + files),
                )
            for (bundle in packages) {
                val diagnostic = bundle.componentDiagnostics.single { it.kind == kind }
                assertEquals(PluginComponentState.UNSUPPORTED, diagnostic.state)
                assertTrue(diagnostic.omitted)
                assertEquals(field, diagnostic.path)
                assertEquals(listOf("guide"), bundle.skills.map { it.directory })
            }
        }
    }

    @Test fun dependenciesAndRuntimeConditionsDoNotResolveNamesOrEchoSecrets() {
        val bundle =
            foreign(
                ",\"dependencies\":[\"secret-package-label\",{\"name\":\"other-secret-label\",\"optional\":true}]" +
                    ",\"userConfig\":{\"api_key\":{\"default\":\"secret-setting-value\"}}",
                mapOf("package.json" to "{}".toByteArray()),
            )
        val diagnostics = bundle.componentDiagnostics
        assertTrue(diagnostics.any { it.requirement == PluginDependencyRequirement.REQUIRED })
        assertTrue(diagnostics.any { it.requirement == PluginDependencyRequirement.OPTIONAL })
        assertTrue(diagnostics.any { it.requirement == PluginDependencyRequirement.RUNTIME })
        assertTrue(diagnostics.any { it.kind == PluginComponentKind.CONFIGURATION })
        assertTrue(
            diagnostics.any { it.path == "package.json" && it.requirement == PluginDependencyRequirement.RUNTIME },
        )
        assertFalse(diagnostics.toString().contains("secret-"))
    }

    @Test fun defaultLspFileIsReportedWithoutAnExplicitManifestDeclaration() {
        val bundle = foreign(files = mapOf(".lsp.json" to "{}".toByteArray()))
        assertTrue(bundle.componentDiagnostics.any { it.kind == PluginComponentKind.LSP && it.omitted })
    }

    @Test fun forkAndHooksStayMetadataAndToolHintsDoNotGrantAuthority() {
        val bytes =
            (
                "---\nname: guide\ndescription: Guide\ncontext: fork\nagent: reviewer\n" +
                    "hooks: {}\nallowed-tools: bash\nmodel: another-model\n---\nDo not execute automatically"
            ).toByteArray()
        val bundle = foreign(files = mapOf("skills/guide/SKILL.md" to bytes))
        assertEquals(
            PluginComponentState.METADATA_ONLY,
            bundle.componentDiagnostics.first { it.code == "SKILL_CONTENT" }.state,
        )
        for (field in listOf("context", "agent", "hooks")) {
            assertTrue(bundle.componentDiagnostics.any { it.path == "skills/guide/SKILL.md/$field" })
        }
        assertTrue(bundle.diagnostics.any { it.startsWith("SKILL_TOOL_POLICY_NOT_APPLIED:") })
        assertTrue(bundle.diagnostics.any { it.startsWith("SKILL_MODEL_NOT_APPLIED:") })
    }

    @Test fun httpsAndLoopbackAreDistinctFromUnsafeRemoteHttpOrUnsupportedSse() {
        for (url in listOf("https://example.invalid/mcp", "http://127.0.0.1:1234/mcp", "http://[::1]:1234/mcp")) {
            assertEquals(
                url,
                foreign(
                    files =
                        mapOf(
                            ".mcp.json" to
                                "{\"mcpServers\":{\"docs\":{\"url\":\"$url\"}}}".toByteArray(),
                        ),
                ).endpoints.single().url,
            )
        }
        for (url in listOf(
            "http://192.168.1.2/mcp",
            "https://user:secret@example.invalid/mcp",
            "https://example.invalid/mcp?token=secret",
        )) {
            val bundle =
                foreign(
                    files =
                        mapOf(
                            ".mcp.json" to
                                "{\"mcpServers\":{\"docs\":{\"url\":\"$url\"}}}".toByteArray(),
                        ),
                )
            assertTrue(bundle.endpoints.isEmpty())
            assertTrue(bundle.componentDiagnostics.any { it.state == PluginComponentState.NEEDS_CONFIGURATION })
            assertFalse(bundle.componentDiagnostics.toString().contains("secret"))
        }
        val sse =
            foreign(
                files =
                    mapOf(
                        ".mcp.json" to
                            """{"mcpServers":{"docs":{
                "type":"sse","url":"https://example.invalid/mcp"
            }}}""".toByteArray(),
                    ),
            )
        assertTrue(sse.endpoints.isEmpty())
        assertTrue(sse.componentDiagnostics.any { it.code == "UNSUPPORTED_TRANSPORT" })
    }

    @Test fun nativeAppMappingAndConfigHaveSeparateReasons() {
        val extension = "\"extensions\":{\"com.helix.agent\":{\"runtime\":\"chat-reply\"}}"
        val native = standard.toString(Charsets.UTF_8).replace("\"name\":\"sample\"", "\"name\":\"sample\",$extension")
        val bundle =
            reader.parse(
                mapOf(
                    "plugin.json" to native.toByteArray(),
                    ".app.json" to "{}".toByteArray(),
                    "skills/guide/SKILL.md" to skill,
                ),
            )
        assertTrue(bundle.componentDiagnostics.any { it.kind == PluginComponentKind.NATIVE && it.omitted })
        assertTrue(bundle.componentDiagnostics.any { it.kind == PluginComponentKind.APP })
        assertFalse(bundle.componentDiagnostics.any { it.code == "APK_NATIVE" })
    }
}
