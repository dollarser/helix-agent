package com.helix.app.proot

import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.ArtifactEntity
import com.helix.core.workspace.ContentProbe
import com.helix.core.workspace.FileScopePath
import com.helix.runtime.proot.core.JobManifestCodec
import com.helix.runtime.proot.core.JobManifestEntry
import com.helix.runtime.proot.core.LiveJobWorkspace
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InterruptedIOException

/** File references are registered in place only while the original observed bytes still match. */
internal object ProotLiveArtifacts {
    fun entries(extracted: File): List<JobManifestEntry> =
        JobManifestCodec.parse(File(extracted, LiveJobWorkspace.OUTPUT_MANIFEST).readText(), Long.MAX_VALUE).entries

    fun reference(
        workspace: String,
        relative: String,
    ): String {
        val root = FileScopePath.fromModelReference(workspace)
        return FileScopePath(
            root.scopeId,
            listOf(root.relativePath, relative).filter(String::isNotEmpty).joinToString("/"),
        ).toModelReference()
    }

    fun register(
        storage: HelixStorage,
        binding: JsonObject,
        turnId: String,
        callId: String,
        extracted: File,
    ): JsonObject {
        val workspace = requireNotNull(binding["workspaceReference"]?.jsonPrimitive?.contentOrNull)
        ProotProducedAuthorization(storage).checkBeforePublish(
            turnId,
            callId,
            FileScopePath.fromModelReference(workspace),
            readOnly = true,
        )
        val root =
            availableWorkspace(storage, binding, workspace)
                ?: return registeredSummary(storage, binding, turnId, emptyList())
        val entries = entries(extracted)
        validateEntries(binding, entries)
        val session = storage.turns.resolve(turnId).sessionId
        val deadline = System.nanoTime() + 10_000_000_000L
        entries.forEach { entry ->
            ProotProducedAuthorization(storage).checkBeforePublish(
                turnId,
                callId,
                FileScopePath.fromModelReference(reference(workspace, entry.path)),
                readOnly = true,
            )
            try {
                val file = LiveJobWorkspace.resolve(root, entry.path)
                require(file.isFile && file.length() == entry.size) {
                    "Artifact changed since the original Job; original result was not replaced"
                }
                val ref = reference(workspace, entry.path)
                val id = "proot-live-${LinuxRunTool.sha256Hex((callId + "\u0000" + ref).toByteArray())}"
                storage.artifacts.registerOrRefreshStream(
                    ArtifactEntity(
                        id,
                        session,
                        ref,
                        ContentProbe.probe(file.toPath()).mimeType,
                        entry.size,
                        entry.sha256,
                        turnId,
                    ),
                ) { LiveArtifactInput(file.inputStream(), deadline) }
            } catch (cancelled: InterruptedIOException) {
                if (Thread.currentThread().isInterrupted) throw cancelled
                // A spent verification budget leaves the file explicitly unregistered; logs still settle.
            } catch (_: IllegalArgumentException) {
                // Changed or unavailable bytes are never registered as the original output.
            } catch (_: java.io.IOException) {
                // The immutable manifest and declared paths keep this omission explicit.
            }
        }
        return registeredSummary(storage, binding, turnId, entries)
    }

    private fun availableWorkspace(
        storage: HelixStorage,
        binding: JsonObject,
        workspace: String,
    ): File? {
        val scope = FileScopePath.fromModelReference(workspace).scopeId
        val resource = storage.workspaces.find(scope)
        if (scope !in setOf("app", "managed") && resource?.availability != "READY") return null
        val root = File(requireNotNull(binding["workspacePath"]?.jsonPrimitive?.contentOrNull))
        return try {
            LiveJobWorkspace.verify(root, requireNotNull(binding["workspaceWitness"]?.jsonPrimitive?.contentOrNull))
            root
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: java.io.IOException) {
            null
        }
    }

    fun registeredSummary(
        storage: HelixStorage,
        binding: JsonObject,
        turnId: String,
        entries: List<JobManifestEntry>,
    ): JsonObject {
        val workspace = requireNotNull(binding["workspaceReference"]?.jsonPrimitive?.contentOrNull)
        val session = storage.turns.resolve(turnId).sessionId
        val requested = validateEntries(binding, entries)
        val registered =
            entries.filter { entry ->
                val row = storage.artifacts.findBySessionAndPath(session, reference(workspace, entry.path))
                row?.turnId == turnId && row.sha256 == entry.sha256 && row.size == entry.size
            }
        val missing = requested.filter { path -> registered.none { it.path == path } }
        return JsonObject(
            summary(workspace, registered) +
                buildJsonObject {
                    put(
                        "unavailableArtifacts",
                        JsonArray(
                            missing.map { path ->
                                buildJsonObject {
                                    put("path", reference(workspace, path))
                                    put("status", "NOT_REGISTERED")
                                }
                            },
                        ),
                    )
                },
        )
    }

    private fun declaredPaths(binding: JsonObject): List<String> {
        val paths = binding["artifactPaths"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
        require(paths.size <= 64 && paths.distinct().size == paths.size)
        return paths
    }

    fun validateEntries(
        binding: JsonObject,
        entries: List<JobManifestEntry>,
    ): List<String> {
        val declared = declaredPaths(binding)
        require(entries.size <= declared.size && entries.map { it.path }.distinct().size == entries.size)
        require(entries.all { it.path in declared }) { "Runtime returned an undeclared artifact" }
        return declared
    }

    fun summary(
        workspace: String,
        entries: List<JobManifestEntry>,
    ): JsonObject =
        buildJsonObject {
            put("artifactCount", entries.size)
            put("artifactsTruncated", false)
            put(
                "artifacts",
                JsonArray(
                    entries.map { entry ->
                        buildJsonObject {
                            put("path", reference(workspace, entry.path))
                            put("sizeBytes", entry.size)
                            put("sha256", entry.sha256)
                        }
                    },
                ),
            )
        }

    private class LiveArtifactInput(
        input: InputStream,
        private val deadline: Long,
    ) : FilterInputStream(input) {
        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (Thread.currentThread().isInterrupted || System.nanoTime() >= deadline) {
                throw InterruptedIOException("Artifact verification cancelled or expired")
            }
            return `in`.read(buffer, offset, length)
        }
    }
}
