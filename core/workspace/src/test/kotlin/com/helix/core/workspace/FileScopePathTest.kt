package com.helix.core.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Path

/**
 * HXA-040: [FileScopePath] is the only file reference the model sees — an opaque scope id plus
 * a canonical relative path. The [ScopeRootResolver]/[resolveFileScopePath] boundary keeps real
 * filesystem locations out of model context (doc 10: 不同 scope adapter 不泄漏真实路径给模型).
 */
class FileScopePathTest {
    // -- construction -----------------------------------------------------------

    @Test
    fun acceptsScopeIdAndCanonicalRelativePath() {
        val path = FileScopePath("ws_01J9ZK4Q7B", "notes/todo.txt")
        assertEquals("ws_01J9ZK4Q7B", path.scopeId)
        assertEquals("notes/todo.txt", path.relativePath)
    }

    @Test
    fun normalizesTheRelativePartAtConstruction() {
        assertEquals("notes/todo.txt", FileScopePath("s", "notes/./todo.txt").relativePath)
        assertEquals("a/b", FileScopePath("s", "a//b").relativePath)
    }

    @Test
    fun rootScopePathIsEmptyRelative() {
        val path = FileScopePath("s", "")
        assertEquals("", path.relativePath)
        assertEquals("scope:s:.", path.toModelReference())
    }

    @Test
    fun rejectsEscapeAndAbsoluteRelativePaths() {
        assertIllegal { FileScopePath("s", "..") }
        assertIllegal { FileScopePath("s", "a/../../b") }
        assertIllegal { FileScopePath("s", "/etc/passwd") }
        assertIllegal { FileScopePath("s", "a" + Char(0).toString() + "b") }
    }

    @Test
    fun rejectsEmptySeparatorOrControlBearingScopeIds() {
        assertIllegal { FileScopePath("", "a") }
        assertIllegal { FileScopePath("a/b", "a") }
        assertIllegal { FileScopePath("a\\b", "a") }
        assertIllegal { FileScopePath("a\nb", "a") }
        assertIllegal { FileScopePath("a".repeat(65), "a") }
    }

    @Test
    fun scopeIdMayNotHoldAReferenceDelimiter() {
        // ':' delimits scope id from the relative part; long refs with colons (e.g. SAF tree
        // URIs) are mapped to a short scope id by the adapter, never embedded here.
        assertIllegal { FileScopePath("saf-tree:content://x/tree/1", "doc.txt") }
        assertIllegal { FileScopePath("a:b", "a") }
    }

    @Test
    fun oversizedReferenceIsRejectedAtConstruction() {
        // The model-reference bound (doc 13) is a fail-closed construction check: an oversized
        // reference is rejected up front rather than throwing from toString(). Reference =
        // "scope:" + scopeId + ":" + relative, so with scopeId "s" (1 char) the reference is
        // 8 + relative.length. Each segment stays <= 255 chars so the *reference* bound (not
        // the segment/count limits) is what fires.
        val tooLong = "a".repeat(255) + "/" + "a".repeat(250) // 506 -> ref 514
        assertIllegal { FileScopePath("s", tooLong) }
        val atLimit = FileScopePath("s", "a".repeat(255) + "/" + "a".repeat(248)) // 504 -> ref 512
        assertEquals(FileScopePath.MAX_MODEL_REFERENCE_LENGTH, atLimit.toModelReference().length)
        // A constructed instance whose reference is within the bound always renders without
        // throwing, including toString().
        assertEquals(atLimit.toModelReference(), atLimit.toString())
    }

    // -- model reference round-trip ----------------------------------------------

    @Test
    fun modelReferenceRoundTrips() {
        val cases =
            listOf(
                FileScopePath("ws_1", "a/b.txt"),
                FileScopePath("ws_1", ""),
                // A long source URI (e.g. a SAF tree URI) never appears verbatim: the adapter
                // maps it to a short scope id, so the model reference stays bounded and
                // unambiguous.
                FileScopePath("allfiles", "download/x.bin"),
            )
        cases.forEach { original ->
            assertEquals(original, FileScopePath.fromModelReference(original.toModelReference()))
        }
    }

    @Test
    fun fromModelReferenceFailsClosedOnMalformedReferences() {
        assertIllegal { FileScopePath.fromModelReference("") }
        assertIllegal { FileScopePath.fromModelReference("scope:only-id") }
        assertIllegal { FileScopePath.fromModelReference("scope::a/b") }
        assertIllegal { FileScopePath.fromModelReference("not-a-ref") }
        // Tampered references are re-normalized, so they cannot smuggle an escape.
        assertIllegal { FileScopePath.fromModelReference("scope:s:../../etc") }
        assertIllegal { FileScopePath.fromModelReference("scope:s:/etc") }
    }

    @Test
    fun navigationHelpersMirrorWorkspacePath() {
        val path = FileScopePath("s", "a/b/c.txt")
        assertEquals(FileScopePath("s", "a/b"), path.parent)
        assertEquals("c.txt", path.name)
        assertEquals(FileScopePath("s", ""), path.parent.parent.parent)
    }

    // -- resolve boundary ----------------------------------------------------------

    private class MapResolver(
        private val roots: Map<String, Path>,
    ) : ScopeRootResolver {
        override fun resolveRoot(scopeId: String): Path =
            roots[scopeId] ?: throw ScopeNotAvailable("scope is not available")
    }

    @Test
    fun unknownScopeFailsClosed() {
        val other = FileScopePath("other", "a.txt")
        val resolver = MapResolver(emptyMap())
        try {
            resolveFileScopePath(other, resolver)
            fail("expected ScopeNotAvailable")
        } catch (expected: ScopeNotAvailable) {
            // expected
        }
    }

    @Test
    fun resolveRootNamesTheScopeNotARawPathInErrors() {
        val path = FileScopePath("gone", "a.txt")
        try {
            resolveFileScopePath(path, MapResolver(emptyMap()))
            fail("expected ScopeNotAvailable")
        } catch (e: ScopeNotAvailable) {
            assertEquals("scope is not available", e.message)
        }
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
