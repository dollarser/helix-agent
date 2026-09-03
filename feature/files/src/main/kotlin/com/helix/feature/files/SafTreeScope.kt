package com.helix.feature.files

import com.helix.core.workspace.ScopeNotAvailable

/**
 * The access mode an operation on a SAF tree scope needs (HXA-057: 读写 mode 复核). A read-only
 * grant refuses a [WRITE] operation fail closed (roadmap: 只读 grant fail closed).
 */
enum class SafAccessMode {
    READ,
    WRITE,
}

/**
 * The read-only view of the persisted SAF tree grants the [SafTreeScopeResolver] verifies against
 * (HXA-057). [SafGrantStore] implements it; tests may inject a fake to exercise the resolver's
 * fail-closed paths (in particular the aliasing / document-id-spoofing check, which the store's
 * own load-time revalidation already enforces — the resolver re-checks at use time as
 * defense-in-depth against a registry mutated after load).
 */
interface SafTreeGrantRegistry {
    /** The grant [scopeId] names, or null. */
    fun find(scopeId: String): SafTreeGrant?

    /** All grants, oldest first. */
    fun list(): List<SafTreeGrant>

    /** The model-visible scope id derived from a tree URI (deterministic; see [SafGrantStore]). */
    fun deriveScopeId(treeUri: String): String
}

/**
 * The live facts of a SAF tree grant, RE-QUERIED ON EVERY USE (HXA-057: 每次使用实时复核 — never
 * cached, never trusted from a stored `GRANTED`). Production: [ContentResolverSafTreeCheck] over a
 * real [android.content.ContentResolver]; tests inject fakes.
 *
 * Every field is untrusted input re-verified by the resolver:
 * - [rootLive] the tree root document is queryable right now (false → 撤销/provider 消失/root 消失);
 * - [authority] the provider identity (the `content://` authority actually serving the tree) —
 *   must equal the authority the grant recorded, else a malicious provider has swapped in;
 * - [rootDocumentId] the tree root document id actually serving the tree — must equal the one the
 *   grant recorded, else the URI/root document has changed;
 * - [readable]/[writable] the live read/write mode (a read-only grant is `writable=false`).
 */
data class SafTreeGrantFacts(
    val rootLive: Boolean,
    val authority: String?,
    val rootDocumentId: String?,
    val readable: Boolean,
    val writable: Boolean,
)

/**
 * The real-time re-verification seam for a persisted SAF tree grant (HXA-057 撤销/provider/URI 复核).
 *
 * A single call answers "can we still open this exact tree grant right now, from the same provider,
 * on the same root document?". The implementation must NEVER return a false success: any failure to
 * query, or a provider that no longer answers, yields `null` (a hard fail the resolver turns into
 * [ScopeNotAvailable]). The non-null facts are still gated by the resolver against the grant's
 * recorded authority/root-document, so a queryable-but-wrong provider is also refused.
 */
fun interface SafTreeGrantCheck {
    /** @return the live facts, or null when the grant no longer answers / the check could not run. */
    fun verify(treeUri: String): SafTreeGrantFacts?
}

/**
 * A verified, model-opaque SAF tree scope (HXA-057: 模型和 Tool 只看到稳定 scopeId 与相对路径).
 *
 * [scopeId] is the ONLY path-shaped value the model may ever see (doc 10); the tree's `content://`
 * URI is never carried in this type and never rendered into model context, tool arguments, logs,
 * user-visible error text, or audit rows. [displayName] is the sanitized, user-facing folder name.
 */
data class SafTreeScope(
    val scopeId: String,
    val displayName: String,
    val readable: Boolean,
    val writable: Boolean,
) {
    /** The model-safe reference of the scope root (doc 10: `scope:<scopeId>:`). */
    fun toModelReference(): String = "scope:$scopeId:."
}

/** One browsable SAF source in the file-manager source list (HXA-057 文件管理来源列表). */
data class SafTreeSource(
    val scopeId: String,
    val displayName: String,
)

/**
 * The unified FileScope resolver for SAF tree scopes (HXA-057: 把 SafGrantStore 接入统一 FileScope
 * resolver). Given a model-visible scope id it RE-VERIFIES the grant in real time and fails closed
 * ([ScopeNotAvailable]) on every failure — never a false success.
 *
 * Re-verification, on EVERY call (doc 02 §9.1: 执行时再查，绝不缓存):
 * 1. the grant exists for [scopeId] (unknown scope → fail);
 * 2. the scopeId is still the one derived from the stored treeUri — a tampered alias (路径/文档 ID
 *    欺骗: pointing a scope id at a different tree) is refused;
 * 3. the provider still answers (撤销/provider 消失/进程死亡重启后失效 → null → fail);
 * 4. the root document still exists ([SafTreeGrantFacts.rootLive]);
 * 5. the provider identity (authority) matches the grant's (恶意 ContentProvider 顶替 → fail);
 * 6. the root document id matches the grant's (URI 变化 → fail);
 * 7. the live mode satisfies [mode] (只读 grant 对写操作 → fail).
 *
 * None of the failure messages include the tree URI (原始 URI 不进入诊断).
 */
class SafTreeScopeResolver(
    private val grants: SafTreeGrantRegistry,
    private val check: SafTreeGrantCheck,
) {
    /**
     * Resolves [scopeId] to a verified [SafTreeScope] satisfying [mode], re-verifying in real time.
     * @throws ScopeNotAvailable on ANY failure (fail closed; the message never names the tree URI).
     */
    @Suppress("ThrowsCount") // one throw per security gate (grant/aliasing/live/root/authority/rootId/mode)
    fun resolve(
        scopeId: String,
        mode: SafAccessMode = SafAccessMode.READ,
    ): SafTreeScope {
        val grant = grants.find(scopeId) ?: throw unavailable(scopeId)
        // 2. Aliasing / document-id spoofing: the scope id must still derive from THIS tree URI.
        if (grants.deriveScopeId(grant.treeUri) != scopeId) throw unavailable(scopeId)
        // 3. Live re-verification: provider gone / hard failure → null → fail closed.
        val facts = check.verify(grant.treeUri) ?: throw unavailable(scopeId)
        // 4. The root document must still exist.
        if (!facts.rootLive) throw unavailable(scopeId)
        // 5. Provider identity (authority) must equal the grant's recorded authority.
        val expectedAuthority = treeUriAuthority(grant.treeUri)
        if (expectedAuthority == null || facts.authority != expectedAuthority) throw unavailable(scopeId)
        // 6. The root document id must equal the grant's recorded one (URI change).
        val expectedRoot = treeUriRootDocumentId(grant.treeUri)
        if (expectedRoot == null || facts.rootDocumentId != expectedRoot) throw unavailable(scopeId)
        // 7. Mode gate: a read-only grant refuses a write op (fail closed).
        val scope = SafTreeScope(scopeId, grant.displayName, facts.readable, facts.writable)
        when (mode) {
            SafAccessMode.READ -> if (!facts.readable) throw unavailable(scopeId)
            SafAccessMode.WRITE -> if (!facts.writable) throw unavailable(scopeId)
        }
        return scope
    }

    /**
     * [resolve] for a read operation, or null when the scope is not currently usable — for the
     * source list: a revoked / provider-gone / tampered / read-unusable grant is omitted, never
     * surfaced (a source the resolver cannot resolve is never offered for browsing).
     */
    @Suppress("SwallowedException") // ScopeNotAvailable is deliberately mapped to null ("not live now")
    fun liveScope(scopeId: String): SafTreeScope? =
        try {
            resolve(scopeId, SafAccessMode.READ)
        } catch (e: ScopeNotAvailable) {
            null
        }
}

private fun unavailable(scopeId: String): ScopeNotAvailable =
    ScopeNotAvailable("SAF tree scope not available: $scopeId")

/**
 * The provider identity (the `content://` authority) of a tree URI. Parsed from the string, not
 * from a provider query, so it is a pure, version-agnostic function (JVM-testable). @return null
 * when [treeUri] is not a well-formed `content://` URI with an authority.
 */
internal fun treeUriAuthority(treeUri: String): String? {
    if (!treeUri.startsWith("content://")) return null
    val after = treeUri.substring("content://".length)
    val slash = after.indexOf('/')
    val authority = if (slash >= 0) after.substring(0, slash) else after
    return authority.ifEmpty { null }
}

/**
 * The tree root document id of a tree URI (the last non-empty path segment). For
 * `content://host/tree/xyz` this is `xyz`; for the platform provider
 * `content://com.android.externalstorage.documents/tree/primary%3ADocuments` it is the URL-encoded
 * `primary%3ADocuments`. Parsed from the string (JVM-testable). @return null when the URI has no
 * path segment to serve as a document id.
 */
internal fun treeUriRootDocumentId(treeUri: String): String? {
    if (!treeUri.startsWith("content://")) return null
    val after = treeUri.substring("content://".length)
    val slash = after.indexOf('/')
    val path = if (slash >= 0) after.substring(slash + 1) else ""
    return path.split('/').filter { it.isNotEmpty() }.lastOrNull()
}
