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
 * [WorkspacePath.value] addresses locations inside this layout; the tool layer never joins real
 * paths itself (doc 10: 工具层不得自行拼接真实路径).
 */
object WorkspaceLayout {
    const val INPUT = "input"
    const val WORK = "work"
    const val OUTPUT = "output"
    const val HELIX = ".helix"
    const val TRASH = "$HELIX/trash"
    const val EXECUTIONS = "$HELIX/executions"
    const val METADATA = "$HELIX/metadata.json"

    /** Every user-visible path region of the layout; nothing else is addressable. */
    val regions: List<String> = listOf(INPUT, WORK, OUTPUT)

    /** True when [region] is one of [regions]. */
    fun isRegion(region: String): Boolean = regions.contains(region)

    /**
     * The first segment of a canonical [relativePath], i.e. the layout region it lives in, or
     * null for the root (the root belongs to no user region). Region membership is a *logical*
     * property of the canonical relative path, not of the real on-disk location, so this check is
     * stable across filesystems where the scope root is itself a symlink (e.g. macOS
     * `/var` → `/private/var`).
     */
    fun regionOf(relativePath: String): String? {
        if (relativePath.isEmpty()) return null
        return relativePath.split('/').first()
    }
}
