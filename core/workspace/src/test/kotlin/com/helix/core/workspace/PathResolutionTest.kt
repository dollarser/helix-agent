package com.helix.core.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * HXA-040: the real-path/containment layer of the scope boundary. A model-canonical relative
 * path must resolve to a location inside the real scope root; symlinks and ancestor chains that
 * escape the root are rejected (security doc: 路径穿越/符号链接逃逸).
 *
 * Platform notes: the symlink cases require symlink support (macOS/Linux/WSL). On a filesystem
 * without symlink support they skip rather than report a false failure.
 */
class PathResolutionTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var base: Path
    private var symlinksSupported = true

    @Suppress("SwallowedException") // probe: UnsupportedOperationException merely records lack of symlink support
    @Before
    fun setUp() {
        base = temp.newFolder("base").toPath()
        try {
            val probe = base.resolve("probe-link")
            Files.createSymbolicLink(probe, base)
            Files.delete(probe)
        } catch (e: UnsupportedOperationException) {
            symlinksSupported = false
        }
    }

    // -- containment: plain paths -------------------------------------------------

    @Test
    fun candidateUnderRootResolvesToItself() {
        val root = base.resolve("ws").also { Files.createDirectories(it) }
        val candidate = root.resolve("notes/todo.txt")
        val resolved = PathResolution.resolveWithinRoot(root, candidate)
        // The real root may differ from the textual root (macOS /var -> /private/var), so
        // compare against the real root plus the same relative tail rather than the textual
        // candidate.
        assertEquals(root.toRealPath().resolve("notes/todo.txt"), resolved)
    }

    @Test
    fun nonexistentCandidateInsideRootIsAllowed() {
        val root = base.resolve("ws").also { Files.createDirectories(it) }
        val candidate = root.resolve("deep/chain/not-yet.txt")
        val resolved = PathResolution.resolveWithinRoot(root, candidate)
        assertEquals(root.toRealPath().resolve("deep/chain/not-yet.txt"), resolved)
    }

    @Test
    fun siblingDirectoryIsRejected() {
        val root = base.resolve("ws").also { Files.createDirectories(it) }
        Files.createDirectories(base.resolve("outside"))
        val candidate =
            base
                .resolve("ws")
                .resolve("..")
                .resolve("outside")
                .resolve("secret.txt")
        try {
            PathResolution.resolveWithinRoot(root, candidate)
            fail("expected SymlinkEscapesRoot")
        } catch (expected: SymlinkEscapesRoot) {
            // expected
        }
    }

    @Test
    fun prefixNameTrickIsRejected() {
        // /base/ws-evil must not be treated as inside /base/ws.
        val root = base.resolve("ws").also { Files.createDirectories(it) }
        val other = base.resolve("ws-evil").also { Files.createDirectories(it) }
        val candidate = other.resolve("a.txt")
        try {
            PathResolution.resolveWithinRoot(root, candidate)
            fail("expected SymlinkEscapesRoot")
        } catch (expected: SymlinkEscapesRoot) {
            // expected
        }
    }

    @Test
    fun fileAncestorInChainIsRejected() {
        val root = base.resolve("ws").also { Files.createDirectories(it) }
        val file = root.resolve("a.txt").also { Files.createFile(it) }
        val candidate = file.resolve("b")
        try {
            PathResolution.resolveWithinRoot(root, candidate)
            fail("expected SymlinkEscapesRoot")
        } catch (expected: SymlinkEscapesRoot) {
            // expected
        }
    }

    // -- symlink rejection ----------------------------------------------------------

    @Test
    fun symlinkAncestorInsideRootIsRejected() {
        skipWithoutSymlinks()
        val root = base.resolve("ws").also { Files.createDirectories(it) }
        val target = base.resolve("target").also { Files.createDirectories(it) }
        Files.createFile(target.resolve("data.txt"))
        val link = root.resolve("link")
        Files.createSymbolicLink(link, target)
        val candidate = link.resolve("data.txt")
        try {
            PathResolution.resolveWithinRoot(root, candidate)
            fail("expected SymlinkInPath")
        } catch (expected: SymlinkInPath) {
            assertEquals("path must not cross a symlinked segment", expected.message)
        }
    }

    @Test
    fun finalSegmentSymlinkIsRejectedByDefaultPolicy() {
        skipWithoutSymlinks()
        val root = base.resolve("ws").also { Files.createDirectories(it) }
        val target = base.resolve("target.txt").also { Files.createFile(it) }
        val link = root.resolve("alias.txt")
        Files.createSymbolicLink(link, target)
        try {
            PathResolution.resolveWithinRoot(root, link)
            fail("expected SymlinkInPath")
        } catch (expected: SymlinkInPath) {
            // expected
        }
    }

    @Test
    fun escapingSymlinkAncestorIsRejected() {
        skipWithoutSymlinks()
        val root = base.resolve("ws").also { Files.createDirectories(it) }
        val outside = base.resolve("outside").also { Files.createDirectories(it) }
        Files.createFile(outside.resolve("secret.txt"))
        val link = root.resolve("leak")
        Files.createSymbolicLink(link, outside)
        val candidate = link.resolve("secret.txt")
        try {
            PathResolution.resolveWithinRoot(root, candidate)
            fail("expected SymlinkInPath")
        } catch (expected: SymlinkInPath) {
            // expected: the policy checks symlinks before resolving them, so even an
            // escaping symlink is reported as a symlink violation, never as a resolved target.
        }
    }

    // -- FOLLOW_ROOT policy -----------------------------------------------------------

    @Test
    fun symlinkedRootIsRejectedByDefaultPolicy() {
        skipWithoutSymlinks()
        val real = base.resolve("real-ws").also { Files.createDirectories(it) }
        Files.createFile(real.resolve("a.txt"))
        val link = base.resolve("ws-link")
        Files.createSymbolicLink(link, real)
        try {
            PathResolution.resolveWithinRoot(link, link.resolve("a.txt"))
            fail("expected SymlinkEscapesRoot")
        } catch (expected: SymlinkEscapesRoot) {
            assertEquals("scope root must not be a symlink", expected.message)
        }
    }

    @Test
    fun symlinkedRootIsFollowedUnderFollowRootPolicy() {
        skipWithoutSymlinks()
        val real = base.resolve("real-ws").also { Files.createDirectories(it) }
        Files.createFile(real.resolve("a.txt"))
        val link = base.resolve("ws-link")
        Files.createSymbolicLink(link, real)
        val resolved =
            PathResolution.resolveWithinRoot(
                link,
                link.resolve("a.txt"),
                LinkPolicy.FOLLOW_ROOT,
            )
        assertEquals(real.toRealPath().resolve("a.txt"), resolved)
    }

    @Test
    fun symlinkedRootStillConfinesEscapeUnderFollowRoot() {
        skipWithoutSymlinks()
        val real = base.resolve("real-ws").also { Files.createDirectories(it) }
        Files.createDirectories(base.resolve("outside"))
        val link = base.resolve("ws-link")
        Files.createSymbolicLink(link, real)
        val candidate = base.resolve("outside").resolve("secret.txt")
        try {
            PathResolution.resolveWithinRoot(link, candidate, LinkPolicy.FOLLOW_ROOT)
            fail("expected SymlinkEscapesRoot")
        } catch (expected: SymlinkEscapesRoot) {
            assertEquals("path escapes the scope root", expected.message)
        }
    }

    @Test
    fun aVanishedScopeRootFailsClosedAsScopeNotAvailableWithoutLeakingTheRealPath() {
        // A revoked scope (SAF tree removed, volume unmounted) reads as ScopeNotAvailable —
        // never as a raw NIO exception carrying the absolute real path (doc 10).
        val gone = base.resolve("never-created")
        try {
            PathResolution.resolveWithinRoot(gone, gone.resolve("work/a.txt"))
            fail("expected ScopeNotAvailable")
        } catch (expected: ScopeNotAvailable) {
            val message = expected.message.orEmpty()
            assertEquals("scope root is not available", message)
            assertFalse("no real path may leak into the error", message.contains(base.toString()))
            assertFalse("no real path may leak into the error", message.contains(gone.toString()))
        }
    }

    // -- join helper -----------------------------------------------------------------

    @Test
    fun joinAppendsCanonicalSegmentsOntoRoot() {
        val root = Paths.get("/data", "workspaces", "ws1")
        assertEquals(root, PathResolution.join(root, ""))
        assertEquals(
            root.resolve("notes").resolve("todo.txt"),
            PathResolution.join(root, "notes/todo.txt"),
        )
    }

    // -- end-to-end through the scope boundary ------------------------------------------

    @Test
    fun resolveFileScopePathBindsCanonicalPathToRealRoot() {
        val root = base.resolve("ws").also { Files.createDirectories(it) }
        Files.createFile(root.resolve("a.txt"))
        val resolver = MapResolver(mapOf("ws1" to root))
        val real = resolveFileScopePath(FileScopePath("ws1", "a.txt"), resolver)
        assertEquals(root.toRealPath().resolve("a.txt"), real)
    }

    @Test
    fun resolveFileScopePathRejectsSymlinkEscapeEndToEnd() {
        skipWithoutSymlinks()
        val root = base.resolve("ws").also { Files.createDirectories(it) }
        val outside = base.resolve("outside").also { Files.createDirectories(it) }
        Files.createFile(outside.resolve("secret.txt"))
        Files.createSymbolicLink(root.resolve("leak"), outside)
        val resolver = MapResolver(mapOf("ws1" to root))
        try {
            resolveFileScopePath(FileScopePath("ws1", "leak/secret.txt"), resolver)
            fail("expected SymlinkInPath")
        } catch (expected: SymlinkInPath) {
            // expected
        }
    }

    private fun skipWithoutSymlinks() {
        if (!symlinksSupported) {
            Assume.assumeTrue("symlinks unsupported on this filesystem", false)
        }
    }

    private class MapResolver(
        private val roots: Map<String, Path>,
    ) : ScopeRootResolver {
        override fun resolveRoot(scopeId: String): Path =
            roots[scopeId] ?: throw ScopeNotAvailable("scope is not available")
    }
}
