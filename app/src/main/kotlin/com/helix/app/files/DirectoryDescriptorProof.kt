package com.helix.app.files

import android.content.ContentResolver
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import java.nio.file.Path

/** No document-id parsing, _data query, URI-to-path table, mutation or file copy. */
internal fun ContentResolver.directoryPathFromDescriptor(
    uri: Uri,
    grantedRoots: () -> List<Pair<String, Path>>,
    recheck: () -> Unit,
): String? =
    try {
        recheck()
        openFileDescriptor(uri, "r")?.use { descriptor ->
            val before = Os.fstat(descriptor.fileDescriptor)
            require(OsConstants.S_ISDIR(before.st_mode))
            val observed = Os.readlink("/proc/self/fd/${descriptor.fd}")
            val result =
                verifiedSharedDirectory(grantedRoots(), observed, before.st_dev to before.st_ino) { path ->
                    Os.stat(path.toString()).let { it.st_dev to it.st_ino }
                }
            recheck()
            val after = Os.fstat(descriptor.fileDescriptor)
            require(before.st_dev == after.st_dev && before.st_ino == after.st_ino)
            result
        }
    } catch (_: java.io.IOException) {
        null
    } catch (_: android.system.ErrnoException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }
