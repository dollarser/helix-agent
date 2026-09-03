package com.helix.feature.files

import android.content.ContentResolver
import android.net.Uri

/**
 * Production [SafTreeGrantCheck] (HXA-057: 每次使用实时复核). Re-queries a persisted SAF tree
 * grant RIGHT NOW and never returns a false success:
 *
 * - **liveness / root document**: a bounded `ContentResolver.query` on the tree root. Revocation,
 *   provider uninstall, a deleted root or a process-death restart in which the grant no longer
 *   answers all surface as a query failure → `null` (the resolver turns it into
 *   `ScopeNotAvailable`, fail closed). A live-but-EMPTY folder still returns a cursor → live
 *   (the same convention HXA-044's `ContentResolverSafGrantProbe` proved on device).
 * - **provider identity / root document id**: the `content://` authority and the tree root
 *   document id of the URI. These are the identity the grant recorded; the resolver pins them and
 *   requires the live facts to match (a tampered grant, or a provider now answering a different
 *   root, fails closed).
 * - **read / write mode**: read = the query above succeeded; write = the URI carries a persisted
 *   WRITE permission. A grant with no write permission (or no persisted permission at all, as for
 *   an in-APK test provider) is `writable=false` → a read-only grant refuses a write operation
 *   fail closed.
 *
 * No field here ever leaks outside the resolver; the raw URI is only ever passed into the
 * `ContentResolver` (原始 URI 不进入模型或诊断).
 */
class ContentResolverSafTreeCheck(
    private val resolver: ContentResolver,
) : SafTreeGrantCheck {
    override fun verify(treeUri: String): SafTreeGrantFacts? {
        val authority = treeUriAuthority(treeUri)
        val rootDocumentId = treeUriRootDocumentId(treeUri)
        return if (authority != null && rootDocumentId != null && isLive(treeUri)) {
            SafTreeGrantFacts(
                rootLive = true,
                authority = authority,
                rootDocumentId = rootDocumentId,
                readable = true,
                writable = hasPersistedWrite(treeUri),
            )
        } else {
            null
        }
    }

    @Suppress("SwallowedException", "TooGenericExceptionCaught") // any query failure reads as revoked (fail closed)
    private fun isLive(treeUri: String): Boolean {
        // A live grant returns a cursor over the tree — even an EMPTY one. Revocation, provider
        // uninstall or a revoked grant surface as a query FAILURE (null cursor or exception), never
        // as a successful-but-empty result (liveness = the cursor, not the row count: an
        // empty-but-granted folder must not read as revoked).
        return runCatching { resolver.query(Uri.parse(treeUri), null, null, null, null)?.use { true } ?: false }
            .getOrDefault(false)
    }

    @Suppress("SwallowedException", "TooGenericExceptionCaught") // no persisted write permission reads as read-only
    private fun hasPersistedWrite(treeUri: String): Boolean =
        try {
            val uri = Uri.parse(treeUri)
            resolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        } catch (e: Exception) {
            false
        }
}
