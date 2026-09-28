package com.helix.extensions.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginManifestTest {
    @Test
    fun parsesAgentPluginsV1AndHelixClientExtension() {
        val manifest =
            PluginManifestReader.parse(
                """
                {
                  "${'$'}schema":"${PluginManifest.AGENT_PLUGINS_V1_SCHEMA}",
                  "name":"mobile-use",
                  "version":"0.1.0",
                  "description":"Mobile UI runtime",
                  "extensions":{"com.helix.agent":{"runtime":"mobile-use"}}
                }
                """.trimIndent().toByteArray(),
            )
        assertEquals("mobile-use", manifest.name)
        assertEquals("0.1.0", manifest.version)
        assertEquals("mobile-use", manifest.helixRuntimeId)
    }

    @Test
    fun unknownRootFieldsAreIgnoredAndNonObjectExtensionsAreNonFatal() {
        val manifest =
            PluginManifestReader.parse(
                """
                {
                  "${'$'}schema":"${PluginManifest.AGENT_PLUGINS_V1_SCHEMA}",
                  "name":"docs",
                  "version":"1.0.0",
                  "description":"Docs",
                  "futureField":true,
                  "extensions":"ignored"
                }
                """.trimIndent().toByteArray(),
            )
        assertNull(manifest.helixRuntimeId)
    }

    @Test
    fun unsupportedSchemaFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            PluginManifestReader.parse(
                """{"${'$'}schema":"https://example.invalid/v9","name":"x","version":"1","description":"x"}"""
                    .toByteArray(),
            )
        }
    }
}
