package com.helix.app.plugin

import com.helix.extensions.plugin.PluginDiagnostics
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.util.zip.ZipException

class PluginImportFailureTest {
    @Test fun knownFailuresMapWithoutReturningSourceText() {
        val cases =
            mapOf(
                "PLUGIN_MARKETPLACE_NOT_PLUGIN" to PluginImportFailure.MARKETPLACE,
                "CONNECTOR_VERSION_CHANGED" to PluginImportFailure.CONFLICT,
                "CONNECTOR_TOO_LARGE" to PluginImportFailure.LIMIT,
                "CONNECTOR_PATH_TRAVERSAL" to PluginImportFailure.UNSAFE_ARCHIVE,
                "CONNECTOR_NO_PORTABLE_COMPONENT" to PluginImportFailure.UNSUPPORTED,
                "CONNECTOR_INVALID_JSON" to PluginImportFailure.INVALID,
            )
        cases.forEach { (code, expected) ->
            assertEquals(
                expected,
                PluginImportFailure.from(IllegalArgumentException("$code: private source and credentials")),
            )
        }
        assertEquals(PluginImportFailure.UNKNOWN, PluginImportFailure.from(Exception("private source")))
    }

    @Test fun archiveAccessAndStorageHaveDistinctRecoveryMessages() {
        assertEquals(PluginImportFailure.INVALID, PluginImportFailure.from(ZipException("private path")))
        assertEquals(PluginImportFailure.ACCESS, PluginImportFailure.from(SecurityException("private path")))
        assertEquals(PluginImportFailure.STORAGE, PluginImportFailure.from(IOException("private path")))
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsNeverConvertedToImportFailure() {
        PluginImportFailure.from(CancellationException())
    }

    @Test fun unsupportedRuntimeAndBinaryDependenciesRemainVisibleWithUsableSkills() {
        val diagnostics =
            listOf(
                "STDIO_REQUIRES_ANDROID_RUNTIME:server",
                "SKILL_REQUIRES_BINARY:dws",
                "AUTH_REQUIRES_CONFIGURATION:server",
            ).map(PluginDiagnostics::project)
        assertEquals(2, limitedPluginComponents(diagnostics))
    }
}
