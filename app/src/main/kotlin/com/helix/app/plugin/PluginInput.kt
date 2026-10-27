package com.helix.app.plugin

import com.helix.extensions.plugin.PluginPackage
import com.helix.extensions.plugin.PluginPackageReader
import java.nio.file.Files
import java.nio.file.Path

/** URI and manual-file sources use the identical bounded parser; file extensions are not authority. */
internal fun previewPluginInput(
    bytes: ByteArray,
    cache: Path,
    reader: PluginPackageReader = PluginPackageReader(),
): PluginPackage {
    require(bytes.size <= PluginPackageReader.MAX_BYTES) { "CONNECTOR_TOO_LARGE" }
    if (bytes.firstOrNull() != 'P'.code.toByte() || bytes.getOrNull(1) != 'K'.code.toByte()) {
        return reader.readJson(bytes)
    }
    Files.createDirectories(cache)
    val temporary = Files.createTempFile(cache, "plugin-input-", ".zip")
    try {
        Files.write(temporary, bytes)
        return reader.readZip(temporary)
    } finally {
        Files.deleteIfExists(temporary)
    }
}
