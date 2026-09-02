package com.helix.feature.files

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
import java.security.MessageDigest

/**
 * A persisted SAF tree grant (the long-lived `DocumentTreeScope` of architecture doc 10).
 *
 * [scopeId] is the ONLY form of this grant the model may ever see (doc 10: 模型只看到
 * scopeId); [treeUri] lives exclusively in this platform-adapter layer and is never rendered
 * into model context, tool arguments or user-visible error text.
 */
data class SafTreeGrant(
    val scopeId: String,
    val treeUri: String,
    val displayName: String,
    val grantedAtMillis: Long,
)

/**
 * Answers "can we still open [treeUri] right now" (撤销检测). The production implementation is a
 * bounded [android.content.ContentResolver] query on the tree root (see
 * `ContentResolverSafGrantProbe`); tests inject fakes.
 */
fun interface SafGrantProbe {
    fun isStillGranted(treeUri: String): Boolean
}

/**
 * The persisted registry of SAF tree grants (HXA-044: persisted tree grant + 撤销检测).
 *
 * Persistence is a single JSON document written through [AtomicFileWriter.writeAtomic]
 * (temp + fsync + atomic replace), so a crash can never leave a half-written registry.
 * On load, a corrupt or structurally invalid registry is QUARANTINED (renamed aside, never
 * deleted) and the store starts empty. Every surviving entry is re-validated against
 * [deriveScopeId]: a tampered registry must not be able to alias one tree grant onto another
 * scope id, so any entry whose (scopeId, treeUri) pair does not check out is dropped.
 *
 * Granting the same tree URI twice is idempotent (same derived scope id; the display name is
 * refreshed, the original grant time kept).
 */
class SafGrantStore(
    private val storePath: Path,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val grants = LinkedHashMap<String, SafTreeGrant>()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        storePath.parent?.let { Files.createDirectories(it) }
        loadExisting()
    }

    /**
     * Records (or refreshes) the grant for [treeUri].
     * @return the persisted grant.
     * @throws IllegalArgumentException when [treeUri] is not a content:// URI.
     */
    fun grant(
        treeUri: String,
        displayName: String,
    ): SafTreeGrant {
        require(treeUri.startsWith("content://")) { "treeUri must be a content:// URI" }
        val scopeId = deriveScopeId(treeUri)
        val name = SafNameSanitizer.sanitize(displayName, fallback = "documents")
        val existing = grants[scopeId]
        val grant =
            if (existing == null) {
                SafTreeGrant(scopeId, treeUri, name, clock())
            } else {
                existing.copy(displayName = name)
            }
        grants[scopeId] = grant
        persist()
        return grant
    }

    /** Removes the grant [scopeId] names. @return true when an entry was removed. */
    fun revoke(scopeId: String): Boolean {
        val removed = grants.remove(scopeId) != null
        if (removed) persist()
        return removed
    }

    /** The grant [scopeId] names, or null. */
    fun find(scopeId: String): SafTreeGrant? = grants[scopeId]

    /** All grants, oldest first. */
    fun list(): List<SafTreeGrant> = grants.values.sortedBy { it.grantedAtMillis }

    /**
     * 撤销检测 sweep: probes every stored tree and removes (persisting) the grants whose
     * provider no longer answers. @return the grants that were revoked, oldest first.
     */
    fun sweepRevoked(probe: SafGrantProbe): List<SafTreeGrant> {
        val revoked = grants.values.filter { !probe.isStillGranted(it.treeUri) }
        if (revoked.isNotEmpty()) {
            revoked.forEach { grants.remove(it.scopeId) }
            persist()
        }
        return revoked.sortedBy { it.grantedAtMillis }
    }

    /**
     * The model-visible scope id of a tree grant: `saf-` + the first 12 hex chars of the
     * SHA-256 of the URI string. Deterministic (granting the same tree yields the same id),
     * opaque, and always a legal [com.helix.core.workspace.FileScopePath] scope id (letters,
     * digits and one hyphen run; no separators or control characters).
     */
    fun deriveScopeId(treeUri: String): String {
        val hex =
            MessageDigest
                .getInstance("SHA-256")
                .digest(treeUri.toByteArray())
                .joinToString("") { "%02x".format(it) }
        return "saf-" + hex.substring(0, 12)
    }

    private fun persist() {
        val document =
            buildJsonObject {
                put("version", FORMAT_VERSION)
                put(
                    "grants",
                    buildJsonArray {
                        grants.values
                            .sortedBy { it.grantedAtMillis }
                            .forEach { g ->
                                add(
                                    buildJsonObject {
                                        put("scopeId", g.scopeId)
                                        put("treeUri", g.treeUri)
                                        put("displayName", g.displayName)
                                        put("grantedAtMillis", g.grantedAtMillis)
                                    },
                                )
                            }
                    },
                )
            }
        AtomicFileWriter.writeAtomic(storePath, json.encodeToString(document).toByteArray())
    }

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    // a corrupt registry must quarantine, not throw: any parse failure starts the store empty
    private fun loadExisting() {
        if (!Files.exists(storePath)) return
        try {
            val parsed = json.parseToJsonElement(Files.readAllBytes(storePath).decodeToString()).jsonObject
            val version = parsed["version"]?.jsonPrimitive?.content?.toLongOrNull()
            if (version != FORMAT_VERSION.toLong()) {
                quarantine()
                return
            }
            (parsed["grants"] as? JsonArray)?.forEach { entry ->
                val grant = entryToGrant(entry) ?: return@forEach
                if (deriveScopeId(grant.treeUri) == grant.scopeId) {
                    grants[grant.scopeId] = grant
                }
            }
        } catch (e: Exception) {
            // Corrupt or structurally invalid registry: quarantine and start empty.
            grants.clear()
            quarantine()
        }
    }

    @Suppress("SwallowedException", "TooGenericExceptionCaught") // malformed entry: dropped, not fatal
    private fun entryToGrant(entry: JsonElement): SafTreeGrant? =
        try {
            val obj = entry.jsonObject
            val treeUri = obj["treeUri"]?.jsonPrimitive?.content ?: return null
            val scopeId = obj["scopeId"]?.jsonPrimitive?.content ?: return null
            val displayName = obj["displayName"]?.jsonPrimitive?.content ?: return null
            val grantedAt = obj["grantedAtMillis"]?.jsonPrimitive?.content?.toLongOrNull() ?: return null
            SafTreeGrant(scopeId, treeUri, displayName, grantedAt)
        } catch (e: Exception) {
            // A structurally malformed entry is dropped, not fatal (fail closed per entry).
            null
        }

    /** Renames the unusable registry aside (never deleted) and starts from an empty store. */
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    // a failing quarantine rename leaves the file; the store is still empty and the next
    // persist() atomically replaces it — no data is lost either way
    private fun quarantine() {
        try {
            Files.move(
                storePath,
                storePath.resolveSibling(storePath.fileName.toString() + ".corrupt-" + clock()),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: Exception) {
            // The file stays as-is; the in-memory store still starts empty and the next
            // persist() atomically replaces the file, so no data is lost either way.
        }
    }

    private companion object {
        const val FORMAT_VERSION = 1
    }
}
