package com.helix.app.files

import java.io.InputStream
import java.io.OutputStream

/** Bounded user-selected export copy; callers retain source permission and stream ownership. */
internal fun copyBackupStream(
    input: InputStream,
    output: OutputStream,
    maximum: Long,
    checkActive: () -> Unit,
) {
    val buffer = ByteArray(8192)
    var total = 0L
    while (true) {
        checkActive()
        val count = input.read(buffer)
        if (count < 0) break
        require(count > 0 && count <= maximum - total) { "BACKUP_FILE_LIMIT" }
        output.write(buffer, 0, count)
        total += count
    }
}
