package com.helix.app.account

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

internal class LocalUserAvatarImporter(
    private val resolver: ContentResolver,
) {
    suspend fun import(uri: Uri): ByteArray {
        require(uri.scheme == "content") { "Choose an image from the system photo picker" }
        val source = readBounded(uri)
        currentCoroutineContext().ensureActive()
        val bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(source))) { decoder, info, _ ->
                configureDecoder(decoder, info)
            }
        try {
            currentCoroutineContext().ensureActive()
            return encodeAvatar(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun readBounded(uri: Uri): ByteArray {
        val input = resolver.openInputStream(uri) ?: throw IOException("Selected image is unavailable")
        return input.use { readStream(it) }
    }

    private suspend fun readStream(stream: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = stream.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_INPUT_BYTES) throw IOException("Image exceeds 10 MiB")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun configureDecoder(
        decoder: ImageDecoder,
        info: ImageDecoder.ImageInfo,
    ) {
        val width = info.size.width
        val height = info.size.height
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS) {
            throw IOException("Image dimensions are too large")
        }
        val scale = minOf(1.0, MAX_EDGE.toDouble() / maxOf(width, height))
        decoder.setTargetSize(maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }

    private fun encodeAvatar(bitmap: Bitmap): ByteArray {
        val output = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
            throw IOException("Cannot decode selected image")
        }
        val bytes = output.toByteArray()
        if (bytes.size > LocalUserProfilePersistence.MAX_AVATAR_BYTES) throw IOException("Image is too large")
        return bytes
    }

    companion object {
        private const val MAX_EDGE = 512
        private const val MAX_PIXELS = 80_000_000L
        private const val MAX_INPUT_BYTES = 10 * 1024 * 1024
    }
}
