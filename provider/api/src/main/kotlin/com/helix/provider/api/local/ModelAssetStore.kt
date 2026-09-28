package com.helix.provider.api.local

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Content-addressed, app-private GGUF assets. A published filename is always the verified digest. */
class ModelAssetStore(
    private val root: File,
    private val quotaBytes: Long = 12L * 1024 * 1024 * 1024,
) {
    private var publicationRevision = 0L
    private var publishing = false

    init {
        require(root.mkdirs() || root.isDirectory)
        require(!Files.isSymbolicLink(root.toPath()))
    }

    @Synchronized
    fun list(): List<ModelAssetRef> =
        root.listFiles().orEmpty().mapNotNull { file ->
            val hash = file.name.removeSuffix(".gguf")
            val validName = file.name == "$hash.gguf" && hash.matches(HASH)
            if (!validName ||
                !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                file.length() !in 1..ModelAssetRef.MAX_ASSET_BYTES
            ) {
                null
            } else {
                ModelAssetRef(hash, hash, file.length())
            }
        }

    @Suppress("NestedBlockDepth") // Bounded streaming into one owned temporary file with guaranteed cleanup.
    @Synchronized
    fun publish(
        sha256: String,
        size: Long,
        source: InputStream,
        checkCancelled: () -> Unit = {},
    ): ModelAssetRef {
        publicationRevision++
        val asset = ModelAssetRef(sha256, sha256, size)
        requireCanPublish(asset)
        val partial = File(root, "$sha256.part")
        require(!Files.isSymbolicLink(partial.toPath()))
        val digest = MessageDigest.getInstance("SHA-256")
        publishing = true
        try {
            partial.outputStream().use { output ->
                val buffer = ByteArray(65536)
                var copied = 0L
                while (true) {
                    checkCancelled()
                    val count = source.read(buffer)
                    if (count < 0) break
                    copied += count
                    require(copied <= size) { "Model asset exceeded declared size" }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
                require(copied == size) { "Model asset incomplete" }
                output.fd.sync()
            }
            require(digest.digest().hex() == sha256) { "Model asset digest mismatch" }
            requireGguf(partial)
            // Cancellation may arrive during the final read, fsync or header validation.
            // Check once more before the atomic publication point; never undo a published asset.
            checkCancelled()
            Files.move(
                partial.toPath(),
                file(asset).toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            return asset
        } finally {
            publishing = false
            Files.deleteIfExists(partial.toPath())
        }
    }

    @Synchronized
    fun publicationResidue(): ModelPublicationResidue =
        ModelPublicationResidue(this, publicationRevision, publicationPartials(root))

    @Synchronized
    fun clearPublicationResidue(snapshot: ModelPublicationResidue) {
        check(!publishing) { "Model publication is active" }
        check(
            snapshot.owner === this && snapshot.revision == publicationRevision &&
                snapshot.entries == publicationPartials(root),
        ) { "Publication files changed; refresh before cleanup" }
        publicationRevision++
        snapshot.entries.forEach { Files.delete(File(root, it.name).toPath()) }
    }

    /** Cheap quota/count preflight. Integrity is still established only by [publish]/[verifiedFile]. */
    @Synchronized
    fun requireCanPublish(asset: ModelAssetRef) {
        val published = list()
        require(published.size < 16 || published.any { it.id == asset.id }) { "Model asset count exceeded" }
        require(
            published.filter { it.id != asset.id }.sumOf { it.sizeBytes } + asset.sizeBytes <= quotaBytes,
        ) { "Model asset quota exceeded" }
    }

    /** Re-check at every runtime handoff; enumeration is not integrity evidence. */
    @Synchronized
    fun verifiedFile(
        asset: ModelAssetRef,
        checkCancelled: () -> Unit = {},
    ): File {
        val file = file(asset)
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && file.length() == asset.sizeBytes)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        require(digest.digest().hex() == asset.sha256) { "Model asset changed" }
        requireGguf(file)
        return file
    }

    /** Caller must hold the runtime load/delete coordination lock and confirm unload first. */
    @Synchronized
    fun delete(asset: ModelAssetRef) {
        Files.deleteIfExists(file(asset).toPath())
    }

    private fun file(asset: ModelAssetRef): File {
        require(asset.id == asset.sha256) { "Asset identity must be its digest" }
        return File(root, "${asset.sha256}.gguf")
    }

    private fun requireGguf(file: File) {
        file.inputStream().use { input ->
            val header = ByteArray(8)
            require(input.read(header) == header.size && header.take(4) == listOf<Byte>(71, 71, 85, 70))
            require(header[4] in 2..3 && header.drop(5).all { it == 0.toByte() }) { "Unsupported GGUF version" }
        }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private companion object {
        val HASH = Regex("[a-f0-9]{64}")
    }
}
