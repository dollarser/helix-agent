package com.helix.core.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * HXA-040: [WorkspacePath] is a value object whose constructor completes every normalization
 * and rejection step of doc 10 — NUL 检查、分隔符归一化、绝对路径拒绝、`./..` 解析、根路径确认.
 *
 * Every special character is built from `Char(code)` so this test source stays pure ASCII and
 * can never carry a raw byte the Kotlin compiler would reject.
 */
class WorkspacePathTest {
    private val nul = Char(0).toString()
    private val lineFeed = Char(10).toString()
    private val tab = Char(9).toString()
    private val del = Char(0x7F).toString()
    private val c1 = Char(0x9F).toString()
    private val backslash = Char(0x5C).toString()
    private val divisionSlash = Char(0x2215).toString()
    private val fractionSlash = Char(0x2044).toString()

    // -- canonical normalization ----------------------------------------------

    @Test
    fun rootIsEmptyAndIsRoot() {
        val path = WorkspacePath("")
        assertTrue(path.isRoot)
        assertEquals("", path.value)
        assertEquals(".", path.name)
    }

    @Test
    fun plainRelativePathIsKept() {
        assertEquals("notes/todo.txt", WorkspacePath("notes/todo.txt").value)
    }

    @Test
    fun dotSegmentsAreResolved() {
        assertEquals("notes/todo.txt", WorkspacePath("notes/./todo.txt").value)
        assertEquals("a.txt", WorkspacePath("./a.txt").value)
        assertEquals("b.txt", WorkspacePath(".//b.txt").value)
    }

    @Test
    fun parentSegmentsAreResolved() {
        assertEquals("docs/todo.txt", WorkspacePath("docs/notes/../todo.txt").value)
        assertEquals("b", WorkspacePath("a/b/../../b").value)
        // Walking up more levels than the path has is an escape above the root.
        assertIllegal { WorkspacePath("a/b/../../..") }
    }

    @Test
    fun trailingDuplicateAndTrailingSeparatorsCollapse() {
        assertEquals("a/b", WorkspacePath("a//b").value)
        assertEquals("a/b", WorkspacePath("a/b/").value)
        assertEquals("a", WorkspacePath("a/").value)
    }

    @Test
    fun deepNestedParentChainResolvesToRoot() {
        val path = WorkspacePath("a/b/c/../d/../../..")
        assertTrue(path.isRoot)
        assertEquals(".", path.name)
    }

    // -- absolute path and separator-variant rejection ------------------------

    @Test
    fun forwardSlashAbsolutePathsAreRejected() {
        assertIllegal { WorkspacePath("/") }
        assertIllegal { WorkspacePath("/etc/passwd") }
    }

    @Test
    fun backslashAbsolutePathsAreRejected() {
        assertIllegal { WorkspacePath(backslash) }
        assertIllegal { WorkspacePath(backslash + "etc" + backslash + "passwd") }
        // Drive-form absolute path (the Windows backslash variant) must not be parsed.
        assertIllegal { WorkspacePath("C:" + backslash + "Users" + backslash + "x") }
    }

    @Test
    fun backslashIsAnOrdinaryCharacterNeverASeparator() {
        // A single backslash inside a segment is a legal filename character, not a separator.
        assertEquals(
            "weird" + backslash + "name.txt",
            WorkspacePath("weird" + backslash + "name.txt").value,
        )
        // The Unicode division signs look like separators but are ordinary characters too.
        assertEquals("a" + divisionSlash + "b", WorkspacePath("a" + divisionSlash + "b").value)
        assertEquals("a" + fractionSlash + "b", WorkspacePath("a" + fractionSlash + "b").value)
    }

    // -- NUL and control character rejection -----------------------------------

    @Test
    fun nullCharactersAreRejected() {
        assertIllegal { WorkspacePath("a" + nul + "b.txt") }
        assertIllegal { WorkspacePath(nul) }
        assertIllegal { WorkspacePath("a" + lineFeed + "b/c") }
        assertIllegal { WorkspacePath("a" + tab + "b") }
        assertIllegal { WorkspacePath("a" + del + "b") }
        assertIllegal { WorkspacePath("a" + c1 + "b") }
    }

    // -- escape above root -----------------------------------------------------

    @Test
    fun parentAboveRootIsRejected() {
        assertIllegal { WorkspacePath("..") }
        assertIllegal { WorkspacePath("../etc") }
        assertIllegal { WorkspacePath("a/../../b") }
    }

    @Test
    fun oversizedPathsAreRejected() {
        assertIllegal { WorkspacePath("a".repeat(256)) }
        assertIllegal {
            WorkspacePath(List(65) { "seg" }.joinToString("/"))
        }
        assertIllegal { WorkspacePath("a".repeat(4097)) }
    }

    // -- navigation helpers -----------------------------------------------------

    @Test
    fun parentAndNameNavigateTheCanonicalForm() {
        val path = WorkspacePath("a/b/c.txt")
        assertEquals(WorkspacePath("a/b"), path.parent)
        assertEquals(WorkspacePath("a"), path.parent.parent)
        assertTrue(path.parent.parent.parent.isRoot)
        assertEquals("c.txt", path.name)
        assertEquals("b", path.parent.name)
    }

    @Test
    fun resolveConcatenatesAndReNormalizes() {
        assertEquals("notes/todo.txt", WorkspacePath("notes").resolve("todo.txt").value)
        assertEquals("notes/a.txt", WorkspacePath("notes/sub").resolve("../a.txt").value)
        assertTrue(WorkspacePath("a/b").resolve("../..").isRoot)
        // Resolving can never produce an escape: .. above the result root is rejected.
        assertIllegal { WorkspacePath("a").resolve("../../etc") }
    }

    @Test
    fun equalityIsOnTheCanonicalForm() {
        assertEquals(WorkspacePath("a/./b/../c"), WorkspacePath("a/c"))
        assertFalse(WorkspacePath("a/c").equals(WorkspacePath("a/d")))
    }

    @Test
    fun toStringIsTheCanonicalRelativePath() {
        assertEquals("a/c", WorkspacePath("a/./c").toString())
    }

    private fun assertIllegal(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }
}
