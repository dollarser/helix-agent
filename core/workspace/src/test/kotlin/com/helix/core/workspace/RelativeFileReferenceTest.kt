package com.helix.core.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeFileReferenceTest {
    @Test fun pathsWithinTheCapturedDirectoryAreShortAndRoundTrip() {
        val directory = FileScopePath("ws", "project")
        for (relative in listOf("", "报告.txt", "docs/report.md", "scope:other:notes", "C:/notes", "\\notes")) {
            val path = FileScopePath("ws", "project/$relative")
            val short = path.referenceRelativeTo(directory)
            assertEquals(path, FileScopePath(directory.scopeId, "${directory.relativePath}/$short"))
        }
        assertEquals(".", directory.referenceRelativeTo(directory))
        assertEquals("报告.txt", FileScopePath("ws", "project/报告.txt").referenceRelativeTo(directory))
        assertEquals(
            "./scope:other:notes",
            FileScopePath("ws", "project/scope:other:notes").referenceRelativeTo(directory),
        )
        assertEquals("./C:/notes", FileScopePath("ws", "project/C:/notes").referenceRelativeTo(directory))
    }

    @Test fun otherScopesParentsAndPrefixSiblingsStayAnchored() {
        val directory = FileScopePath("ws", "project")
        val paths =
            listOf(FileScopePath("other", "project/a"), FileScopePath("ws", ""), FileScopePath("ws", "projects/a"))
        for (path in paths) {
            assertEquals(path.toModelReference(), path.referenceRelativeTo(directory))
        }
        assertEquals("docs/a", FileScopePath("ws", "docs/a").referenceRelativeTo(FileScopePath("ws", "")))
    }
}
