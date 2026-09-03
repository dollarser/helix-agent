package com.helix.feature.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HXA-058: [SafTreeImportPlanner] — the fail-closed name mapping of a SAF tree listing onto
 * workspace `input/` targets (every display name is untrusted provider input, doc 07).
 */
class SafTreeImportPlannerTest {
    private fun entry(vararg segments: String) = entryS(-1L, *segments)

    private fun entryS(
        size: Long,
        vararg segments: String,
    ) = SafTreeImportEntry(segments.toList(), size, "content://p/doc-" + segments.joinToString("_"))

    // ── Normal mapping ───────────────────────────────────────────────────────────────────

    @Test
    fun aFlatAndNestedTreeMapsToInputTargetsInOrder() {
        val plan = SafTreeImportPlanner.plan(listOf(entryS(5L, "a.txt"), entryS(1L, "sub", "b.txt")))
        assertEquals(listOf("input/a.txt", "input/sub/b.txt"), plan.planned.map { it.targetRelativePath })
        assertTrue(plan.skipped.isEmpty())
        assertEquals("content://p/doc-a.txt", plan.planned.first().documentUri)
        assertEquals(5L, plan.planned.first().sizeBytes)
    }

    @Test
    fun evilDisplayNamesAreSanitizedBeforeTheyBecomePathSegments() {
        val plan = SafTreeImportPlanner.plan(listOf(entry("../../etc", "evil\u0000name.txt")))
        assertEquals("input/.._.._etc/evilname.txt", plan.planned.single().targetRelativePath)
    }

    @Test
    fun aNameThatSanitizesToNothingFallsBackButStillImports() {
        // An all-control-character name sanitizes to the fallback; a single fallback sibling is
        // unambiguous (separators sanitize to underscores, not the fallback).
        val plan = SafTreeImportPlanner.plan(listOf(entry("\u0000\u0001")))
        assertEquals("input/imported-file", plan.planned.single().targetRelativePath)
    }

    @Test
    fun aSeparatorOnlyNameSanitizesToUnderscoresNotTheFallback() {
        val plan = SafTreeImportPlanner.plan(listOf(entry("///")))
        assertEquals("input/___", plan.planned.single().targetRelativePath)
    }

    // ── Ambiguity (never guess) ──────────────────────────────────────────────────────────

    @Test
    fun twoSiblingsThatSanitizeToTheSameNameAreBothSkipped() {
        val plan =
            SafTreeImportPlanner.plan(
                listOf(
                    entry("a_b", "f.txt"),
                    entry("a/b", "f.txt"), // "a/b" sanitizes to "a_b" — a collision in the workspace
                ),
            )
        assertTrue(plan.planned.isEmpty())
        assertEquals(
            listOf(
                SafTreeImportPlanner.ImportSkipReason.AMBIGUOUS_NAME,
                SafTreeImportPlanner.ImportSkipReason.AMBIGUOUS_NAME,
            ),
            plan.skipped.map { it.reason },
        )
    }

    @Test
    fun aDirectoryCollidingWithAFilenameSkipsEveryFileBeneathIt() {
        // The tree has a FILE "dir" at the root AND a DIRECTORY "dir" (both sanitize to "dir"):
        // the files under the directory cannot be mapped without guessing → all skipped.
        val plan =
            SafTreeImportPlanner.plan(
                listOf(
                    entry("dir", "f.txt"),
                    entry("dir", "nested", "g.txt"),
                    entry("dir"), // a file with the directory's name
                ),
            )
        assertTrue(plan.planned.isEmpty())
        assertEquals(3, plan.skipped.size)
        assertEquals(
            List(3) { SafTreeImportPlanner.ImportSkipReason.AMBIGUOUS_NAME },
            plan.skipped.map { it.reason },
        )
    }

    @Test
    fun distinctNamesInTheSameDirectoryImportAndDuplicatesSkip() {
        val clean = SafTreeImportPlanner.plan(listOf(entry("d", "s1.txt"), entry("d", "s2.txt")))
        assertEquals(listOf("input/d/s1.txt", "input/d/s2.txt"), clean.planned.map { it.targetRelativePath })
        val dupes = SafTreeImportPlanner.plan(listOf(entry("d", "s.txt"), entry("d", "s.txt")))
        assertTrue(dupes.planned.isEmpty())
        assertEquals(2, dupes.skipped.size)
        assertEquals(SafTreeImportPlanner.ImportSkipReason.AMBIGUOUS_NAME, dupes.skipped.first().reason)
    }

    // ── Bounds ───────────────────────────────────────────────────────────────────────────

    @Test
    fun aFileDeeperThanTheDepthCapIsSkippedWithAVisibleReason() {
        val deep = (1..SafTreeImportPlanner.MAX_DEPTH + 1).map { "d$it" }
        val plan = SafTreeImportPlanner.plan(listOf(entry(*deep.toTypedArray(), "f.txt")))
        assertTrue(plan.planned.isEmpty())
        assertEquals(SafTreeImportPlanner.ImportSkipReason.TOO_DEEP, plan.skipped.single().reason)
    }

    @Test
    fun aFileExactlyAtTheDepthCapIsImported() {
        // MAX_DEPTH counts the file's own segment: MAX_DEPTH-1 directories + the file.
        val deep = (1..SafTreeImportPlanner.MAX_DEPTH - 1).map { "d$it" }
        val plan = SafTreeImportPlanner.plan(listOf(entry(*deep.toTypedArray(), "f.txt")))
        assertEquals(1, plan.planned.size)
    }

    @Test
    fun aListingAboveTheFileCapSkipsTheSurplusInOrder() {
        val entries = (1..SafTreeImportPlanner.MAX_FILES + 2).map { entry("f$it.txt") }
        val plan = SafTreeImportPlanner.plan(entries)
        assertEquals(SafTreeImportPlanner.MAX_FILES, plan.planned.size)
        assertEquals(2, plan.skipped.size)
        assertEquals("f10001.txt", plan.skipped.first().sourceLabel)
        assertEquals(
            SafTreeImportPlanner.ImportSkipReason.TOO_MANY_FILES,
            plan.skipped.first().reason,
        )
    }
}
