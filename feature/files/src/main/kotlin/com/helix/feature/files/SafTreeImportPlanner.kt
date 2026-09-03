package com.helix.feature.files

import com.helix.core.workspace.WorkspaceLayout

/**
 * Plans the WORKSPACE target paths for a SAF tree import (HXA-058 文件夹导入). Pure JVM logic
 * over the raw [SafTreeLister] listing: every provider display name is untrusted input
 * (doc 07) and is sanitized with [SafNameSanitizer] before it becomes a path segment.
 *
 * Fail-closed name mapping (the planner NEVER guesses):
 * - a sibling pair that sanitizes to the SAME (parent dir, child name) is ambiguous — every file
 *   under BOTH of those siblings is skipped ([ImportSkipReason.AMBIGUOUS_NAME]) (e.g. children
 *   `a_b` and `a/b` both sanitize to `a_b`; duplicate display names; a directory and a file with
 *   colliding names);
 * - a file deeper than [MAX_DEPTH] segments is skipped ([ImportSkipReason.TOO_DEEP]);
 * - a listing with more than [MAX_FILES] files skips the surplus, in listing order
 *   ([ImportSkipReason.TOO_MANY_FILES]) — the enumeration bound already fails the whole import
 *   for pathological trees, this bound caps the per-destination fan-out.
 *
 * Targets stay inside the `input/` user region: `input/<seg1>/<seg2>/.../<leaf>` (a tree import
 * recreates the folder structure under `input/`; the pipeline's own containment + region checks
 * apply to each file).
 */
object SafTreeImportPlanner {
    /** Files (not directories) the plan will import; the surplus is skipped with a visible reason. */
    const val MAX_FILES = 10_000

    /** Path segments below which a file is imported (deeper files are skipped, never guessed). */
    const val MAX_DEPTH = 32

    /** One importable file with its planned workspace target. */
    data class PlannedImport(
        val targetRelativePath: String,
        val sourceLabel: String,
        val sizeBytes: Long,
        val documentUri: String,
    )

    /** A file the plan refuses to map onto a target path (the import result reports it, visibly). */
    data class SkippedImport(
        val sourceLabel: String,
        val reason: ImportSkipReason,
    )

    /** Why a listed file is not imported (stable codes the facade maps to user-visible text). */
    enum class ImportSkipReason {
        /** A name collision after sanitization: the source cannot be mapped without guessing. */
        AMBIGUOUS_NAME,

        /** The file sits deeper than [MAX_DEPTH] segments below the tree root. */
        TOO_DEEP,

        /** The listing holds more than [MAX_FILES] files; the surplus is not imported. */
        TOO_MANY_FILES,
    }

    /**
     * The complete plan: [planned] in listing order + [skipped] (every skipped file is reported,
     * so a tree import never silently omits a file the user could not see).
     */
    data class Plan(
        val planned: List<PlannedImport>,
        val skipped: List<SkippedImport>,
    )

    /**
     * Plans [entries] (the [SafTreeLister] output) into workspace `input/` targets. Each
     * un-importable file is one of three DISTINCT stable skips (never merged, never guessed).
     */
    @Suppress("LoopWithTooManyJumpStatements") // three distinct fail-closed skips, one per stable reason
    fun plan(entries: List<SafTreeImportEntry>): Plan {
        val seen = siblingCounts(entries)
        val planned = mutableListOf<PlannedImport>()
        val skipped = mutableListOf<SkippedImport>()
        var importedCount = 0
        for (entry in entries) {
            val label = entry.rawSegments.joinToString("/")
            if (entry.rawSegments.size > MAX_DEPTH) {
                skipped.add(SkippedImport(label, ImportSkipReason.TOO_DEEP))
                continue
            }
            if (importedCount >= MAX_FILES) {
                skipped.add(SkippedImport(label, ImportSkipReason.TOO_MANY_FILES))
                continue
            }
            val sanitized = sanitize(entry)
            if (hasAmbiguousAncestor(sanitized, seen)) {
                skipped.add(SkippedImport(label, ImportSkipReason.AMBIGUOUS_NAME))
                continue
            }
            importedCount++
            planned.add(
                PlannedImport(
                    targetRelativePath = WorkspaceLayout.INPUT + "/" + sanitized.joinToString("/"),
                    sourceLabel = sanitized.joinToString("/"),
                    sizeBytes = entry.sizeBytes,
                    documentUri = entry.documentUri,
                ),
            )
        }
        return Plan(planned, skipped)
    }

    /**
     * Sibling collision counts keyed by (parent dir, child name) after sanitization. Directory
     * nodes count ONCE (many files share an ancestor directory — that is not a collision); FILE
     * entries count per entry (two files that sanitize to the same target collide). A count > 1
     * means two siblings collide in the workspace → ambiguous.
     */
    private fun siblingCounts(entries: List<SafTreeImportEntry>): Map<String, Int> {
        val dirNodes = HashSet<List<String>>()
        for (entry in entries) {
            val sanitized = sanitize(entry)
            for (i in 1 until sanitized.size) {
                dirNodes.add(sanitized.subList(0, i))
            }
        }
        val seen = HashMap<String, Int>()
        for (node in dirNodes) {
            seen[nodeKey(node)] = (seen[nodeKey(node)] ?: 0) + 1
        }
        val fileCounts = HashMap<String, Int>()
        for (entry in entries) {
            val full = sanitize(entry)
            fileCounts[nodeKey(full)] = (fileCounts[nodeKey(full)] ?: 0) + 1
        }
        for ((key, count) in fileCounts) {
            seen[key] = (seen[key] ?: 0) + count
        }
        return seen
    }

    /** Whether [sanitized] or any of its ancestor sibling pairs is a collision per [seen]. */
    private fun hasAmbiguousAncestor(
        sanitized: List<String>,
        seen: Map<String, Int>,
    ): Boolean =
        (1 until sanitized.size).any { i -> (seen[nodeKey(sanitized.subList(0, i))] ?: 0) > 1 } ||
            (seen[nodeKey(sanitized)] ?: 0) > 1

    private fun sanitize(entry: SafTreeImportEntry): List<String> =
        entry.rawSegments.map { SafNameSanitizer.sanitize(it) }

    /**
     * The sibling key of the sanitized node path [node]: parent path + name joined with a NUL
     * (the sanitizer strips every control character, so no sanitized name can contain the
     * separator — different (parent, name) pairs can never collide in the key).
     */
    private fun nodeKey(node: List<String>): String {
        val name = node.last()
        val parent = node.dropLast(1).joinToString("/")
        return parent + "\u0000" + name
    }
}
