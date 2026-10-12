package com.helix.app.marketplace

import com.helix.extensions.plugin.PluginMarketplace
import com.helix.extensions.plugin.PluginMarketplaceReader
import com.helix.extensions.plugin.PluginPackageReader
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class MarketplaceSourcePreview internal constructor(
    val catalog: PluginMarketplace,
    internal val bytes: ByteArray,
    internal val previousHash: String?,
)

/** User-imported discovery snapshots only. Installed plugin facts remain exclusively in Room. */
class MarketplaceSourceStore(
    private val root: Path,
) {
    @Synchronized
    fun list(): List<PluginMarketplace> {
        if (!Files.exists(root)) return emptyList()
        return Files.list(root).use { paths ->
            paths
                .filter { it.fileName.toString().endsWith(".catalog") }
                .sorted()
                .map { path ->
                    require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "MARKETPLACE_SOURCE_NOT_FILE" }
                    MarketplaceSnapshotCodec.decode(read(path))
                }.iterator()
                .asSequence()
                .toList()
        }
    }

    @Synchronized
    fun preview(bytes: ByteArray): MarketplaceSourcePreview {
        val captured = bytes.copyOf()
        val catalog = parse(captured)
        return MarketplaceSourcePreview(
            catalog,
            captured,
            list().singleOrNull { it.identity == catalog.identity }?.contentHash,
        )
    }

    @Synchronized
    fun add(
        preview: MarketplaceSourcePreview,
        cancelled: () -> Boolean = { false },
    ): PluginMarketplace {
        val catalog = parse(preview.bytes)
        require(catalog.contentHash == preview.catalog.contentHash && catalog.identity == preview.catalog.identity) {
            "MARKETPLACE_CONTENT_CHANGED"
        }
        val current = list().singleOrNull { it.identity == catalog.identity }
        if (current?.contentHash == catalog.contentHash) return current
        require(current?.contentHash == preview.previousHash) { "MARKETPLACE_SOURCE_CHANGED" }
        check(!cancelled()) { "IMPORT_CANCELLED" }
        Files.createDirectories(root)
        val target = sourcePath(catalog.identity)
        val prepared = MarketplaceSnapshotCodec.encode(catalog)
        val storedBytes =
            Files.list(root).use { paths ->
                paths
                    .filter { it != target && it.fileName.toString().endsWith(".catalog") }
                    .mapToLong(Files::size)
                    .sum()
            }
        require(storedBytes + prepared.size <= MAX_STORED_BYTES) { "MARKETPLACE_STORAGE_LIMIT" }
        val temporary = Files.createTempFile(root, ".pending-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { file ->
                file.write(prepared)
                file.fd.sync()
            }
            check(!cancelled()) { "IMPORT_CANCELLED" }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return catalog
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    @Synchronized
    fun remove(
        identity: String,
        expectedHash: String,
    ) {
        val current = list().singleOrNull { it.identity == identity } ?: return
        require(current.contentHash == expectedHash) { "MARKETPLACE_SOURCE_CHANGED" }
        Files.delete(sourcePath(identity))
    }

    @Synchronized
    fun candidate(
        identity: String,
        hash: String,
        name: String,
    ) = requireNotNull(list().singleOrNull { it.identity == identity && it.contentHash == hash }) {
        "MARKETPLACE_SOURCE_CHANGED"
    }.entries.single { it.name == name }

    private fun sourcePath(identity: String): Path =
        root.resolve(PluginPackageReader.digest(mapOf("identity" to identity.toByteArray())) + ".catalog")

    private fun read(path: Path): ByteArray =
        Files.newInputStream(path).use { PluginPackageReader.readBounded(it, MAX_STORED_BYTES.toInt()) }

    private fun parse(bytes: ByteArray): PluginMarketplace {
        require(bytes.size <= PluginPackageReader.MAX_BYTES) { "CONNECTOR_TOO_LARGE" }
        val reader = PluginMarketplaceReader()
        if (bytes.take(2) != listOf('P'.code.toByte(), 'K'.code.toByte())) return reader.readJson(bytes)
        Files.createDirectories(root)
        val temporary = Files.createTempFile(root, ".read-", ".zip")
        return try {
            Files.write(temporary, bytes)
            reader.readZip(temporary)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    companion object {
        private const val MAX_STORED_BYTES = 64 * 1024 * 1024L
    }
}
