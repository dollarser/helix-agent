package com.helix.feature.files

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri

/** Releases an exact persisted grant, never a parent, child, or another source's permission. */
class ContentResolverSafPermissionRelease(
    private val resolver: ContentResolver,
) {
    fun release(treeUri: String) {
        val uri = Uri.parse(treeUri)
        val permission = resolver.persistedUriPermissions.singleOrNull { it.uri == uri } ?: return
        val flags =
            (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        resolver.releasePersistableUriPermission(uri, flags)
        check(resolver.persistedUriPermissions.none { it.uri == uri }) { "SAF permission could not be released" }
    }
}
