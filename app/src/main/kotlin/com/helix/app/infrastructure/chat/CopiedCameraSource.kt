package com.helix.app.infrastructure.chat

import android.content.Context
import androidx.core.net.toUri
import com.helix.core.workspace.AtomicFileWriter
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Removes only an owned camera transport copy after identical bytes have been registered durably. */
internal class CopiedCameraSource(
    context: Context,
) {
    private val authority = "${context.packageName}.fileprovider"
    private val directory = File(context.filesDir, "attachments/camera")

    @Suppress("ReturnCount") // Each failed ownership/identity check retains the source.
    fun release(
        uri: String,
        copiedSha256: String,
    ) {
        val source = uri.toUri()
        if (source.scheme != "content" || source.authority != authority) return
        val segments = source.pathSegments
        if (segments.size != 2 || segments[0] != "camera-captures") return
        val name = segments[1]
        if (!name.matches(Regex("helix-camera-[a-zA-Z0-9-]+\\.jpg"))) return
        val file = File(directory, name)
        try {
            if (file.canonicalFile.parentFile != directory.canonicalFile ||
                !Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)
            ) {
                return
            }
            if (AtomicFileWriter.sha256Hex(file.toPath()) == copiedSha256) Files.delete(file.toPath())
        } catch (_: IOException) {
            // Retain the redundant transport file on cleanup failure; never remove the durable snapshot.
        }
    }
}
