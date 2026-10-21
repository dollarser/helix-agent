package com.helix.app.proot

import com.helix.runtime.proot.core.JobManifestEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProotLiveArtifactsTest {
    private val binding = Json.parseToJsonElement("""{"artifactPaths":["report.docx","src/main.py"]}""").jsonObject

    @Test fun originalDeclaredReferencesKeepTheirNamesAndVersions() {
        val entry = JobManifestEntry("report.docx", "a".repeat(64), 100)
        assertEquals(listOf("report.docx", "src/main.py"), ProotLiveArtifacts.validateEntries(binding, listOf(entry)))
        val summary = ProotLiveArtifacts.summary("scope:original:project", listOf(entry))
        val file =
            summary
                .getValue("artifacts")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("scope:original:project/report.docx", file.getValue("path").jsonPrimitive.content)
        assertEquals(entry.sha256, file.getValue("sha256").jsonPrimitive.content)
    }

    @Test fun runtimeMetadataDoesNotGrantAuthorityForExtraOrDuplicateFiles() {
        val undeclared = JobManifestEntry(".helix/private", "a".repeat(64), 1)
        assertThrows(IllegalArgumentException::class.java) {
            ProotLiveArtifacts.validateEntries(binding, listOf(undeclared))
        }
        val declared = undeclared.copy(path = "report.docx")
        assertThrows(IllegalArgumentException::class.java) {
            ProotLiveArtifacts.validateEntries(binding, listOf(declared, declared))
        }
    }

    @Test fun missingDeclaredOutputIsNotInventedAsACapturedFile() {
        assertEquals(2, ProotLiveArtifacts.validateEntries(binding, emptyList()).size)
        assertEquals(
            0,
            ProotLiveArtifacts
                .summary("scope:original:project", emptyList())
                .getValue("artifactCount")
                .jsonPrimitive.content
                .toInt(),
        )
    }
}
