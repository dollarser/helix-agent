package com.helix.app.marketplace

import android.net.Uri
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun writeMarketplaceFixture(
    root: Path,
    name: String,
    version: String,
): Uri {
    val archive = root.resolve("source-$version.zip")
    ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
        val files =
            mapOf(
                ".claude-plugin/marketplace.json" to
                    """{"name":"$name-market","plugins":[{"name":"$name","source":"./pkg"}]}""",
                "pkg/.claude-plugin/plugin.json" to """{"name":"$name","version":"$version"}""",
                "pkg/skills/$name/SKILL.md" to "---\nname: $name\ndescription: Owned lifecycle\n---\nVersion $version",
            )
        files.forEach { (path, text) ->
            zip.putNextEntry(ZipEntry(path))
            zip.write(text.toByteArray())
            zip.closeEntry()
        }
    }
    return Uri.fromFile(archive.toFile())
}

internal fun removeMarketplaceFixture(root: Path) {
    Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
}
