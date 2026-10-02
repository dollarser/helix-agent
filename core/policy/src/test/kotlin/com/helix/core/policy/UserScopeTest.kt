package com.helix.core.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

/**
 * HXA-032: the six user scope types of doc 9 section 2 are value types with fail-closed
 * construction — a structurally invalid scope cannot be built, and the audit ref is canonical
 * and bounded.
 */
class UserScopeTest {
    private val instant = Instant.parse("2026-09-01T12:00:00Z")

    // -- WorkspaceScope -------------------------------------------------------

    @Test
    fun workspaceScopeAcceptsAValidSessionId() {
        val scope = WorkspaceScope("ws-01J9ZK4Q7B")
        assertEquals("workspace:ws-01J9ZK4Q7B", scope.toScopeRef())
    }

    @Test
    fun workspaceScopeRejectsBlankOverlongOrIllegalIds() {
        assertIllegal { WorkspaceScope("") }
        assertIllegal { WorkspaceScope("x".repeat(65)) }
        assertIllegal { WorkspaceScope("ws with space") }
        assertIllegal { WorkspaceScope("ws/01") }
    }

    // -- DocumentTreeScope ----------------------------------------------------

    @Test
    fun documentTreeScopeAcceptsASafTreeUri() {
        val scope = DocumentTreeScope("content://com.android.externalstorage.documents/tree/123%3A456", "My Folder")
        assertEquals("saf-tree:content://com.android.externalstorage.documents/tree/123%3A456", scope.toScopeRef())
    }

    @Test
    fun documentTreeScopeRejectsNonTreeOrNonContentUris() {
        assertIllegal { DocumentTreeScope("http://example.com/tree/1", "x") }
        assertIllegal { DocumentTreeScope("content://auth/document/1", "x") }
        assertIllegal { DocumentTreeScope("content://auth/tree/", "x") }
        assertIllegal { DocumentTreeScope("content://auth/tree/1", "") }
        assertIllegal { DocumentTreeScope("content://auth/tree/1", "x".repeat(129)) }
    }

    // -- SharedStorageScope ---------------------------------------------------

    @Test
    fun sharedStorageScopeAcceptsCanonicalRootsAndSortsTheRef() {
        val scope = SharedStorageScope(listOf("/storage/emulated/0/Download", "/sdcard/Pictures"))
        assertEquals("allfiles:/sdcard/Pictures;/storage/emulated/0/Download", scope.toScopeRef())
    }

    @Test
    fun sharedStorageScopeAcceptsTheFilesystemRoot() {
        assertEquals("allfiles:/", SharedStorageScope(listOf("/")).toScopeRef())
    }

    @Test
    fun sharedStorageScopeRejectsNonCanonicalOrDuplicateRoots() {
        assertIllegal { SharedStorageScope(emptyList()) }
        assertIllegal { SharedStorageScope(listOf("relative/path")) }
        assertIllegal { SharedStorageScope(listOf("/a/../b")) }
        assertIllegal { SharedStorageScope(listOf("/a/.")) }
        assertIllegal { SharedStorageScope(listOf("/a//b")) }
        assertIllegal { SharedStorageScope(listOf("/a/")) }
        assertIllegal { SharedStorageScope(listOf("/a", "/a")) }
        assertIllegal { SharedStorageScope(listOf("/" + "x".repeat(513))) }
    }

    // -- BrowserTabScope ------------------------------------------------------

    @Test
    fun browserTabScopeAcceptsTabIdAndGeneration() {
        val scope = BrowserTabScope("tab-1", 7)
        assertEquals("browser-tab:tab-1:gen=7", scope.toScopeRef())
    }

    @Test
    fun browserTabScopeRejectsBlankTabIdOrNegativeGeneration() {
        assertIllegal { BrowserTabScope("", 0) }
        assertIllegal { BrowserTabScope("x".repeat(65), 0) }
        assertIllegal { BrowserTabScope("tab 1", 0) }
        assertIllegal { BrowserTabScope("tab-1", -1) }
    }

    // -- AutomationSessionScope ------------------------------------------------

    @Test
    fun automationSessionScopeAcceptsValidTargetsAndBudget() {
        val scope =
            AutomationSessionScope(
                allowedPackages = setOf("com.example.chat"),
                deniedPackages = setOf("com.example.payments"),
                maxActions = 30,
                expiresAt = instant,
            )
        assertEquals(
            "automation:all=false:grant=:allowed=com.example.chat:denied=com.example.payments:max=30:expires=$instant",
            scope.toScopeRef(),
        )
    }

    @Test
    fun automationSessionScopeRejectsEmptyTargetsBadPackagesAndConflicts() {
        assertIllegal { AutomationSessionScope(emptySet(), emptySet(), 30, instant) }
        val mixedCase = AutomationSessionScope(setOf("com.example.Caps"), emptySet(), 30, instant)
        assertTrue(mixedCase.permitsPackage("com.example.Caps"))
        assertFalse(mixedCase.permitsPackage("com.example.caps"))
        assertIllegal { AutomationSessionScope(setOf("1com.example"), emptySet(), 30, instant) }
        assertIllegal { AutomationSessionScope(setOf("com..example"), emptySet(), 30, instant) }
        assertIllegal {
            AutomationSessionScope(
                setOf("com.example.a", "com.example.b"),
                setOf("com.example.b"),
                30,
                instant,
            )
        }
        assertIllegal { AutomationSessionScope(setOf("com.example.a"), emptySet(), -1, instant) }
        assertEquals(10_001, AutomationSessionScope(setOf("com.example.a"), emptySet(), 10_001, instant).maxActions)
    }

    @Test fun wholePhoneScopeIsExplicitAndGrantIdentitySurvivesRoundTrip() {
        val phone = AutomationSessionScope(emptySet(), setOf("com.private.app"), 0, Instant.MAX, true, "grant-1")
        assertTrue(phone.permitsPackage("com.any.app"))
        assertFalse(phone.permitsPackage("com.private.app"))
        assertFalse(phone.permitsPackage(""))
        assertEquals(phone, UserScopeCodec.decode(UserScopeCodec.encode(phone)))
        assertNotEquals(phone.toScopeRef(), phone.copy(grantId = "grant-2").toScopeRef())
        assertIllegal { phone.copy(allApplications = false) }
        assertIllegal { phone.copy(allowedPackages = setOf("com.any.app")) }
    }

    @Test fun automationDeadlinePrecisionIsPartOfTheGrant() {
        val scope = AutomationSessionScope(setOf("com.example.app"), emptySet(), 0, instant.plusNanos(123))
        assertEquals(scope, UserScopeCodec.decode(UserScopeCodec.encode(scope)))
        assertNotEquals(scope.toScopeRef(), scope.copy(expiresAt = instant.plusNanos(124)).toScopeRef())
    }

    // -- RootSessionScope -------------------------------------------------------

    @Test
    fun rootSessionScopeAcceptsABoundedSessionAndDefaultsHighLevelOnly() {
        val scope = RootSessionScope(instant, instant.plusSeconds(600))
        assertTrue(scope.highLevelToolsOnly)
        assertEquals("root:expires=${instant.plusSeconds(600).epochSecond}:high-level=true", scope.toScopeRef())
    }

    @Test
    fun rootSessionScopeRejectsInvertedOrOverlongSessions() {
        assertIllegal { RootSessionScope(instant.plusSeconds(60), instant) }
        assertIllegal { RootSessionScope(instant, instant.plusSeconds(61 * 60)) }
        // boundary: zero-length session is structurally allowed, the product default is not zero
        RootSessionScope(instant, instant)
    }

    // -- audit refs -------------------------------------------------------------

    @Test
    fun scopeRefsAreStableSortedAndDistinct() {
        val a1 = AutomationSessionScope(setOf("com.b.app", "com.a.app"), emptySet(), 30, instant)
        val a2 = AutomationSessionScope(setOf("com.a.app", "com.b.app"), emptySet(), 30, instant)
        assertEquals(a1.toScopeRef(), a2.toScopeRef())
        assertNotEquals(
            a1.toScopeRef(),
            AutomationSessionScope(setOf("com.b.app", "com.a.app"), setOf("com.z"), 30, instant).toScopeRef(),
        )
        assertNotEquals(WorkspaceScope("ws-1").toScopeRef(), BrowserTabScope("ws-1", 0).toScopeRef())
    }

    @Test
    fun scopeRefIsBoundedAtMaxScopeRefLength() {
        val manyPackages = (1..40).map { "com.helix.package${it.toString().padStart(3, '0')}.module" }.toSet()
        val scope = AutomationSessionScope(manyPackages, emptySet(), 30, instant, grantId = "selection")
        assertTrue(scope.toScopeRef().length <= UserScope.MAX_SCOPE_REF_LENGTH)
        assertTrue(scope.toScopeRef().startsWith("automation:sha256:"))
        assertEquals(scope, UserScopeCodec.decode(UserScopeCodec.encode(scope)))
        assertEquals(scope.toScopeRef(), scope.copy(allowedPackages = manyPackages.reversed().toSet()).toScopeRef())
        assertNotEquals(scope.toScopeRef(), scope.copy(allowedPackages = manyPackages + "com.extra.app").toScopeRef())
        assertNotEquals(scope.toScopeRef(), scope.copy(grantId = "new-grant").toScopeRef())
        assertNotEquals(scope.toScopeRef(), scope.copy(expiresAt = instant.plusNanos(1)).toScopeRef())
        assertFalse(scope.permitsPackage("com.unselected.app"))
    }

    private fun assertIllegal(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }
    }
}
