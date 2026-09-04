package com.helix.runtime.proot.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Strict codec for the job-archive manifest entry (HXA-084; same fail-closed
 * JsonElement house style as the HXA-080 codecs). Unknown or missing keys, wrong
 * types, malformed hashes/sizes/paths all throw [JobArchiveException].
 *
 * The encoding is canonical (fixed key order, sorted entries) so both APKs can
 * hash-compare the same manifest without reserialization ambiguity.
 */
object JobManifestCodec {
    /** The only supported manifest schema version. */
    const val SUPPORTED_SCHEMA_VERSION: Int = 1

    private val json = Json { isLenient = false }

    /** Canonical encoding: fixed key order, entries sorted by path. */
    fun encode(manifest: JobManifest): String {
        require(manifest.entries.isEmpty() || manifest.entries == manifest.entries.sortedBy { it.path }) {
            "manifest entries must be sorted by path"
        }
        val obj =
            buildJsonObject {
                put("schemaVersion", SUPPORTED_SCHEMA_VERSION)
                put(
                    "entries",
                    buildJsonArray {
                        manifest.entries.forEach { entry ->
                            add(
                                buildJsonObject {
                                    put("path", entry.path)
                                    put("sha256", entry.sha256)
                                    put("size", entry.size)
                                },
                            )
                        }
                    },
                )
            }
        return obj.toString()
    }

    /**
     * Parses and validates; throws [JobArchiveException] on ANY violation. One
     * throw per distinct schema violation; the parse is a single strict read
     * of a bounded document (long by necessity, not to be fragmented).
     */
    @Suppress("ThrowsCount", "LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught", "SwallowedException")
    fun parse(text: String): JobManifest {
        if (text.length > JobArchiveLimits.MAX_MANIFEST_BYTES) {
            throw JobArchiveException("manifest exceeds the size cap")
        }
        val root =
            try {
                json.parseToJsonElement(text) as? JsonObject
                    ?: throw JobArchiveException("manifest is not a JSON object")
            } catch (e: JobArchiveException) {
                throw e
            } catch (e: Exception) {
                throw JobArchiveException("manifest is not valid JSON: ${e.message}")
            }
        if (root.keys != setOf("schemaVersion", "entries")) {
            throw JobArchiveException("manifest has unknown or missing keys")
        }
        val schemaVersion =
            (root["schemaVersion"] as? JsonPrimitive)?.longOrNull
                ?: throw JobArchiveException("manifest schemaVersion is not an integer")
        if (schemaVersion !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            throw JobArchiveException("manifest schemaVersion out of range")
        }
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION.toLong()) {
            throw JobArchiveException("unsupported manifest schemaVersion: $schemaVersion")
        }
        val entriesElement =
            root["entries"] as? JsonArray
                ?: throw JobArchiveException("manifest entries is not an array")
        val entries =
            entriesElement.mapIndexed { index, element ->
                val entry =
                    element as? JsonObject
                        ?: throw JobArchiveException("manifest entry $index is not an object")
                if (entry.keys != setOf("path", "sha256", "size")) {
                    throw JobArchiveException("manifest entry $index has unknown or missing keys")
                }
                val path =
                    (entry["path"] as? JsonPrimitive)?.content
                        ?: throw JobArchiveException("manifest entry $index path is not a string")
                val sha256 =
                    (entry["sha256"] as? JsonPrimitive)?.content
                        ?: throw JobArchiveException("manifest entry $index sha256 is not a string")
                val size =
                    (entry["size"] as? JsonPrimitive)?.longOrNull
                        ?: throw JobArchiveException("manifest entry $index size is not a long")
                JobPath.validate(path)
                if (!JsonSchemaSupport.SHA256_PATTERN.matches(sha256)) {
                    throw JobArchiveException("manifest entry $index has a malformed sha256")
                }
                if (size < 0 || size > JobArchiveLimits.MAX_SINGLE_FILE_BYTES) {
                    throw JobArchiveException("manifest entry $index has an out-of-bounds size")
                }
                JobManifestEntry(path, sha256, size)
            }
        // Sorted + unique: the canonical form requires both.
        entries
            .zipWithNext { a, b -> a.path < b.path }
            .indexOfFirst { !it }
            .let { if (it >= 0) throw JobArchiveException("manifest entries are not sorted/unique") }
        return JobManifest(entries)
    }
}
