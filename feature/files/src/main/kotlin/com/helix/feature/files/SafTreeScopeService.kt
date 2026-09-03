package com.helix.feature.files

/**
 * The SAF tree scope service (HXA-057): the single seam the app's file manager and scope
 * resolution use to manage persisted SAF tree grants. It wraps [SafGrantStore] (the persisted,
 * model-opaque grant registry) and a [SafTreeScopeResolver] (real-time re-verification), and
 * exposes only model-safe forms — a `content://` URI never leaves this class except to the
 * [SafGrantCheck] seam it drives (原始 URI 不进入模型或诊断).
 *
 * The re-verification is on EVERY call: [liveSources] re-resolves each stored grant before
 * surfacing it, and [resolve] re-resolves on every operation (doc 02 §9.1: 执行时再查，绝不缓存).
 * A grant whose provider no longer answers, whose root document has gone, whose authority/root
 * changed, or that does not satisfy the requested mode is omitted or refused — never offered.
 */
class SafTreeScopeService(
    private val store: SafGrantStore,
    private val check: SafTreeGrantCheck,
) {
    private val resolver = SafTreeScopeResolver(store, check)

    /**
     * The SAF tree scopes the file-manager source list can browse RIGHT NOW (HXA-057 文件管理来源
     * 列表). Each stored grant is re-verified for a read operation; a revoked / provider-gone /
     * tampered / read-unusable grant is omitted, so the list never offers a scope the resolver
     * cannot actually resolve. Oldest grant first.
     */
    fun liveSources(): List<SafTreeSource> =
        store
            .list()
            .mapNotNull { grant ->
                resolver.liveScope(grant.scopeId)?.let { SafTreeSource(it.scopeId, it.displayName) }
            }

    /**
     * Resolves [scopeId] to a verified [SafTreeScope] satisfying [mode] (HXA-057: 每次使用实时复核).
     * Fail closed ([ScopeNotAvailable]) on every failure.
     */
    fun resolve(
        scopeId: String,
        mode: SafAccessMode = SafAccessMode.READ,
    ): SafTreeScope = resolver.resolve(scopeId, mode)

    /** The stored grant's model-safe scope id + display name, or null when unknown (no URI). */
    fun source(scopeId: String): SafTreeSource? {
        val grant = store.find(scopeId) ?: return null
        return SafTreeSource(grant.scopeId, grant.displayName)
    }

    /** Removes the grant [scopeId] names (the file manager's 移除 entry). @return true when removed. */
    fun revoke(scopeId: String): Boolean = store.revoke(scopeId)

    /**
     * Records (or refreshes) a grant for a user-selected tree (the [android.app.action 重新授权]
     * result: `ACTION_OPEN_DOCUMENT_TREE` → this). The caller supplies the picker's tree URI; it is
     * stored model-opaquely (the derived scope id is the only form the model sees).
     * @throws IllegalArgumentException when [treeUri] is not a content:// URI.
     */
    fun grant(
        treeUri: String,
        displayName: String,
    ): SafTreeGrant = store.grant(treeUri, displayName)

    /**
     * The 撤销检测 sweep: probes every stored tree and removes (persisting) the grants whose
     * provider no longer answers. @return the revoked grants, oldest first.
     */
    fun sweepRevoked(): List<SafTreeGrant> = store.sweepRevoked(SafGrantProbe { uri -> check.verify(uri) != null })

    /**
     * A read-only, model-opaque summary of the scopes currently in the registry (whether or not
     * still live) — for diagnostics that must never leak a URI.
     */
    fun knownScopeIds(): List<String> = store.list().map { it.scopeId }
}
