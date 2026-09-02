package com.helix.feature.files.allfiles

import com.helix.core.workspace.AtomicFileWriter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * An enabled all-files root (HXA-045). [scopeId] is the ONLY form the model may see (doc 10);
 * [realPath] lives exclusively in the platform-adapter layer and is never rendered into model
 * context, tool arguments, or user-visible error text.
 */
data class AllFilesRootGrant(
    val key: String,
    val scopeId: String,
    val realPath: String,
    val enabledAtMillis: Long,
)

/**
 * The persisted registry of enabled all-files roots (HXA-045 "Helix roots").
 *
 * Mirrors the SAF grant store's guarantees: a single JSON document written through
 * [AtomicFileWriter.writeAtomic] (temp + fsync + atomic replace); a corrupt or structurally
 * invalid registry is QUARANTINED (renamed aside, never deleted) and the store starts empty;
 * every surviving entry is re-validated against [AllFilesRootCatalog.scopeId] so a tampered
 * registry cannot alias one root onto another scope id. Enabling a root requires its key to be in
 * the CLOSED catalog — an arbitrary path can never be registered, which is what keeps the agent's
 * reach bounded to the user's explicit choices.
 */
class AllFilesRootsStore(
    private val storePath: Path,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val roots = LinkedHashMap<String, AllFilesRootGrant>()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        storePath.parent?.let { Files.createDirectories(it) }
        loadExisting()
    }

    /**
     * Enables (or re-resolves) the catalog root [key] at its resolved [realPath].
     *
     * @return the persisted grant.
     * @throws IllegalArgumentException when [key] is not a catalog root (fail closed) or
     *   [realPath] is not absolute.
     */
    fun enable(
        key: String,
        realPath: String,
    ): AllFilesRootGrant {
        require(AllFilesRootCatalog.byKey(key) != null) { "unknown all-files root: $key" }
        require(realPath.startsWith("/")) { "realPath must be absolute: $realPath" }
        val scopeId = AllFilesRootCatalog.scopeId(key)
        val existing = roots[scopeId]
        val grant =
            if (existing == null) {
                AllFilesRootGrant(key, scopeId, realPath, clock())
            } else {
                existing.copy(realPath = realPath)
            }
        roots[scopeId] = grant
        persist()
        return grant
    }

    /** Disables the root [key] names. @return true when an entry was removed. */
    fun disable(key: String): Boolean {
        val scopeId = AllFilesRootCatalog.scopeId(key)
        val removed = roots.remove(scopeId) != null
        if (removed) {
            persist()
        }
        return removed
    }

    fun isEnabled(key: String): Boolean = roots.containsKey(AllFilesRootCatalog.scopeId(key))

    /** The enabled root [scopeId] names, or null. */
    fun find(scopeId: String): AllFilesRootGrant? = roots[scopeId]

    /** All enabled roots, oldest first. */
    fun list(): List<AllFilesRootGrant> = roots.values.sortedBy { it.enabledAtMillis }

    /** The real root path for a scope id, or null when that root is not enabled. */
    fun resolveScopeRoot(scopeId: String): Path? = find(scopeId)?.realPath?.let { Path.of(it) }

    private fun persist() {
        val document =
            buildJsonObject {
                put("version", FORMAT_VERSION)
                put(
                    "roots",
                    buildJsonArray {
                        roots.values
                            .sortedBy { it.enabledAtMillis }
                            .forEach { g ->
                                add(
                                    buildJsonObject {
                                        put("key", g.key)
                                        put("scopeId", g.scopeId)
                                        put("realPath", g.realPath)
                                        put("enabledAtMillis", g.enabledAtMillis)
                                    },
                                )
                            }
                    },
                )
            }
        AtomicFileWriter.writeAtomic(storePath, json.encodeToString(document).toByteArray())
    }

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    // a corrupt registry must quarantine, not throw: any parse/IO failure starts the store empty
    private fun loadExisting() {
        if (!Files.exists(storePath)) {
            return
        }
        try {
            val parsed =
                json.parseToJsonElement(Files.readAllBytes(storePath).decodeToString()).jsonObject
            val version = parsed["version"]?.jsonPrimitive?.content?.toLongOrNull()
            if (version != FORMAT_VERSION.toLong()) {
                quarantine()
                return
            }
            (parsed["roots"] as? JsonArray)?.forEach { entry ->
                val grant = entryToGrant(entry) ?: return@forEach
                if (AllFilesRootCatalog.scopeId(grant.key) == grant.scopeId) {
                    roots[grant.scopeId] = grant
                }
            }
        } catch (e: Exception) {
            roots.clear()
            quarantine()
        }
    }

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    // an un-atomicable quarantine (e.g. read-only dir) must not crash the app: log + keep empty
    private fun quarantine() {
        val fileName = storePath.fileName.toString()
        val target = storePath.resolveSibling("$fileName.corrupt-${clock()}")
        try {
            Files.move(storePath, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            // fall through: leave the unreadable file, the store stays empty (fail closed)
        }
    }

    private fun entryToGrant(element: JsonElement): AllFilesRootGrant? {
        val obj = element as? JsonObject ?: return null
        val key = obj["key"]?.jsonPrimitive?.content
        val scopeId = obj["scopeId"]?.jsonPrimitive?.content
        val realPath = obj["realPath"]?.jsonPrimitive?.content
        val enabledAt = obj["enabledAtMillis"]?.jsonPrimitive?.content?.toLongOrNull()
        val complete = listOf(key, scopeId, realPath, enabledAt).none { it == null }
        return if (complete) AllFilesRootGrant(key!!, scopeId!!, realPath!!, enabledAt!!) else null
    }

    private companion object {
        const val FORMAT_VERSION = 1
    }
}
