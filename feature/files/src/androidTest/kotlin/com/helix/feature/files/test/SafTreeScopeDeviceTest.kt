package com.helix.feature.files.test

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.workspace.ScopeNotAvailable
import com.helix.feature.files.ContentResolverSafTreeCheck
import com.helix.feature.files.SafAccessMode
import com.helix.feature.files.SafGrantStore
import com.helix.feature.files.SafTreeScopeService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * HXA-057 (device gate): the REAL [ContentResolverSafTreeCheck] (real-time re-verification) driven
 * through the REAL `ContentResolver` against the in-APK [LyingContentProvider] tree cases — the
 * same code path a hostile / revoked / gone provider would take. The grant registry persists to a
 * real file, so the 进程死亡/重启 (process restart) case reopens it in a FRESH [SafGrantStore]
 * instance. A `content://` URI never reaches a model-visible or diagnostic surface (doc 10).
 */
class SafTreeScopeDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver

    private fun storePath(tag: String): Path =
        Files
            .createDirectories(context.cacheDir.toPath().resolve("saf-scope-it-$tag"))
            .resolve("saf-grants.json")

    private fun service(tag: String): SafTreeScopeService =
        SafTreeScopeService(SafGrantStore(storePath(tag)) { 0L }, ContentResolverSafTreeCheck(resolver))

    private fun treeUri(case: String): String = "content://${LyingContentProvider.AUTHORITY}/$case"

    // granted-tree is live; READ resolves, WRITE fails closed (no persisted WRITE permission).
    @Test
    fun aGrantedTreeResolvesReadButRefusesWrite() {
        val svc = service("read")
        val grant = svc.grant(treeUri("granted-tree"), "Granted")
        assertEquals(grant.scopeId, svc.resolve(grant.scopeId, SafAccessMode.READ).scopeId)
        assertThrows(
            "a grant without a persisted WRITE permission must refuse a write operation",
            ScopeNotAvailable::class.java,
        ) { svc.resolve(grant.scopeId, SafAccessMode.WRITE) }
    }

    // denied-tree: the provider throws → the check returns null → ScopeNotAvailable (revoked / gone).
    @Test
    fun aDeniedTreeFailsClosedAndIsOmittedFromSources() {
        val svc = service("denied")
        val grant = svc.grant(treeUri("denied-tree"), "Denied")
        assertThrows(ScopeNotAvailable::class.java) { svc.resolve(grant.scopeId, SafAccessMode.READ) }
        assertTrue(
            "a grant whose provider no longer answers is never offered as a live source",
            svc.liveSources().isEmpty(),
        )
    }

    // empty-tree: a LIVE grant over an EMPTY folder (a 0-row cursor) is NOT revoked — liveness is
    // the cursor, not the row count (the revocation-probe regression).
    @Test
    fun anEmptyButGrantedTreeIsLiveNotRevoked() {
        val svc = service("empty")
        val grant = svc.grant(treeUri("empty-tree"), "Empty")
        assertEquals(grant.scopeId, svc.resolve(grant.scopeId, SafAccessMode.READ).scopeId)
    }

    // 进程死亡/重启: a FRESH store instance reloaded from disk re-resolves a still-live grant.
    @Test
    fun aGrantedTreeSurvivesAProcessRestart() {
        val path = storePath("restart")
        val beforeRestart = SafTreeScopeService(SafGrantStore(path) { 0L }, ContentResolverSafTreeCheck(resolver))
        val scopeId = beforeRestart.grant(treeUri("granted-tree"), "Persisted").scopeId

        val afterRestart = SafTreeScopeService(SafGrantStore(path) { 0L }, ContentResolverSafTreeCheck(resolver))
        assertEquals(scopeId, afterRestart.resolve(scopeId, SafAccessMode.READ).scopeId)
        assertEquals(scopeId, afterRestart.liveSources().single().scopeId)
    }

    // grant 泄漏: no `content://` URI in any model-visible surface, and none in a failure message.
    @Test
    fun noRawUriLeaksThroughAnyModelVisibleSurface() {
        val svc = service("leak")
        val liveUri = treeUri("granted-tree")
        val grant = svc.grant(liveUri, "Leak")
        val surfaces =
            listOf(
                grant.scopeId,
                svc.source(grant.scopeId)?.displayName.orEmpty(),
                svc.liveSources().joinToString { it.displayName + it.scopeId },
                svc.knownScopeIds().joinToString(),
            )
        for (surface in surfaces) {
            assertFalse("model-visible surface must not leak the URI: $surface", surface.contains("content://"))
            assertFalse("model-visible surface must not leak the tree: $surface", surface.contains(liveUri))
        }
        // A failure (the denied tree) must also leak nothing.
        val denied = svc.grant(treeUri("denied-tree"), "Denied2")
        val message =
            runCatching { svc.resolve(denied.scopeId, SafAccessMode.READ) }
                .exceptionOrNull()
                ?.message
                .orEmpty()
        assertFalse("failure message must not leak the URI: $message", message.contains("content://"))
    }
}
