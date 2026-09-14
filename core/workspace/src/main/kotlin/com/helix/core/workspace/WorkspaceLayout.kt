package com.helix.core.workspace

/**
 * Fixed workspace directory layout (architecture doc section 10):
 *
 * ```text
 * workspaces/<workspace-id>/
 * ├── input/
 * ├── work/
 * ├── output/
 * └── .helix/
 *     ├── metadata.json
 *     ├── trash/
 *     └── executions/
 * ```
 *
 * A canonical [FileScopePath.relativePath] addresses locations inside this layout; the tool layer
 * never joins real paths itself (doc 10: 工具层不得自行拼接真实路径).
 */
object WorkspaceLayout {
    const val INPUT = "input"
    const val WORK = "work"
    const val OUTPUT = "output"
    const val HELIX = ".helix"
    const val TRASH = "$HELIX/trash"
    const val EXECUTIONS = "$HELIX/executions"
    const val METADATA = "$HELIX/metadata.json"
    const val ROOT_FILES = "<root-files>"

    /** Conventional directories created by ensureLayout; root user files need no extra directory. */
    val regions: List<String> = listOf(INPUT, WORK, OUTPUT)

    /** True when [region] is one of [regions]. */
    fun isRegion(region: String): Boolean = regions.contains(region) || region == ROOT_FILES

    /**
     * The conventional region or ROOT_FILES for ordinary root entries; null for the root
     * itself and reserved .helix internals. Region membership is a *logical*
     * property of the canonical relative path, not of the real on-disk location, so this check is
     * stable across filesystems where the scope root is itself a symlink (e.g. macOS
     * `/var` → `/private/var`).
     */
    fun regionOf(relativePath: String): String? {
        if (relativePath.isEmpty()) return null
        val first = relativePath.split('/').first()
        return when {
            first == HELIX -> null
            first in regions -> first
            else -> ROOT_FILES
        }
    }
}
