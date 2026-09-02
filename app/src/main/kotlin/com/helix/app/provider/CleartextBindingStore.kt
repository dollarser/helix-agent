package com.helix.app.provider

import com.helix.app.internal.LineStore
import com.helix.provider.api.CleartextAuthorization

/**
 * The user's explicit, per-host:port cleartext authorizations (doc 10 section 2.5;
 * ADR-0005: "授权绑定 host + port，不是全局 cleartext 开关").
 *
 * A binding is created ONLY when the user reads the risk display for a concrete
 * `host:port` and confirms it while creating/editing a provider that talks
 * http to that host:port. It is revoked automatically when no provider
 * references the host:port anymore (provider deleted or re-pointed), so an
 * authorization can never outlive the endpoint the user approved. The set is
 * fed to [CleartextAuthorization.isPermitted] at send time; https endpoints
 * need no binding.
 *
 * Stored as normalized `host:port` lines (host lowercased; IPv6 as its bare literal).
 * The port is ALWAYS present: a bare host line cannot be distinguished from an IPv6
 * literal (which contains colons), so `all()` splits on the last colon — that split is
 * only unambiguous when the port is written, which is why the port is never omitted.
 */
class CleartextBindingStore(
    store: LineStore,
) {
    private val backing = store

    /** All currently authorized host:port pairs. */
    fun all(): Set<CleartextAuthorization> =
        backing
            .lines(KEY)
            .mapNotNull { line ->
                val idx = line.lastIndexOf(':')
                if (idx <= 0 || idx == line.length - 1) {
                    null
                } else {
                    CleartextAuthorization(
                        line.substring(0, idx),
                        line.substring(idx + 1).toIntOrNull() ?: return@mapNotNull null,
                    )
                }
            }.toSet()

    /** Records the user's explicit confirmation for [auth]. Idempotent. */
    fun authorize(auth: CleartextAuthorization) {
        val current = backing.lines(KEY).toMutableList()
        val encoded = encode(auth)
        if (encoded !in current) current += encoded
        backing.setLines(KEY, current)
    }

    /**
     * Keeps only [keep]; drops every other binding. Called after provider
     * create/edit/delete with the host:ports still referenced by persisted
     * providers, so unreferenced authorizations are revoked.
     */
    fun pruneTo(keep: Set<CleartextAuthorization>) {
        val kept = keep.map(::encode).toSet()
        val current = backing.lines(KEY).filter { it in kept }
        backing.setLines(KEY, current)
    }

    private fun encode(auth: CleartextAuthorization): String = "${auth.host}:${auth.port}"

    private companion object {
        const val KEY = "cleartext_bindings"
    }
}
