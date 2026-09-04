package com.helix.tools.android

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The on-device verification gate for HXA-064 (verification-matrix row
 * `:tools:android:connectedDebugAndroidTest`).
 *
 * What the device proves here, against the PRODUCTION [AndroidSystemBridgeImpl]:
 * - a REAL system [android.content.ClipboardManager] write path (`setPrimaryClip` on the live
 *   service) plus the visible-foreground gate: clipboard read/write REFUSE when the
 *   [ForegroundProbe] reports not-foreground. (The read-back round-trip and the 4 000-char read
 *   bound are unit-tested on the JVM — a library's instrumented test runs in a background test app
 *   with no visible Activity, and API 29+ redacts `getPrimaryClip` for a non-foreground app, so an
 *   on-device read-back would be flaky by construction.)
 * - `android.open_uri` builds the REAL `ACTION_VIEW` intent (correct data + `CATEGORY_BROWSABLE`)
 *   and launches it, but REFUSES every non-http(s) scheme WITHOUT building/launching any intent;
 * - `android.share` builds the REAL `ACTION_CHOOSER` wrapping an `ACTION_SEND` carrying the share
 *   text (and subject), never picking a target app.
 *
 * The [ForegroundProbe] and [IntentLauncher] are injected so the test asserts the real intent
 * content and the real clipboard behaviour WITHOUT actually launching another app or depending on
 * flaky emulator foreground state; the production defaults (real `ActivityManager` probe + real
 * `Context` launcher) are what the app container uses. The real [ActivityManagerForegroundProbe] is
 * still exercised on-device as a no-throw smoke (its value depends on the live process state).
 */
@RunWith(AndroidJUnit4::class)
class AndroidSystemBridgeDeviceTest {
    private lateinit var context: android.content.Context
    private lateinit var probe: ToggleProbe
    private lateinit var launcher: RecordingLauncher
    private lateinit var bridge: AndroidSystemBridgeImpl

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        probe = ToggleProbe()
        launcher = RecordingLauncher()
        bridge = AndroidSystemBridgeImpl(context, probe, launcher)
    }

    // ── clipboard: real round-trip + bound, gated by foreground ──────────────────────────

    @Test
    fun clipboardWriteReachesTheRealClipboardManager() {
        probe.fg = true
        val text = "write-path check ✓ 你好 🎉 12345"
        val written = bridge.clipboardWrite(text)
        // Exercises the REAL android.content.ClipboardManager.setPrimaryClip (a binder call to the
        // system service); WRITTEN is only returned if it did not throw. The READ-back round-trip is
        // deliberately NOT asserted on device: this library's instrumented test runs in a background
        // test app (no visible Activity), and API 29+ redacts getPrimaryClip for a non-foreground app,
        // so a read-back would be flaky by construction. The read bound is unit-tested on the JVM.
        assertEquals(ClipboardWriteStatus.WRITTEN, written.status)
        assertEquals(text.length, written.length)
        assertEquals("", written.reason)
    }

    @Test
    fun clipboardReadRefusedWhenNotForeground() {
        probe.fg = false
        val read = bridge.clipboardRead()
        assertEquals(ClipboardReadStatus.REFUSED, read.status)
        assertEquals("not-foreground", read.reason)
        assertEquals("", read.text)
    }

    @Test
    fun clipboardWriteRefusedWhenNotForeground() {
        probe.fg = false
        val written = bridge.clipboardWrite("should not be written")
        assertEquals(ClipboardWriteStatus.REFUSED, written.status)
        assertEquals("not-foreground", written.reason)
        assertEquals(0, written.length)
    }

    // ── android.open_uri: real ACTION_VIEW, http/https only ───────────────────────────────

    @Test
    fun openUriLaunchesAnActionViewIntentWithTheHttpUrl() {
        probe.fg = true
        val out = bridge.openUri("https://example.com/some/path?x=1")
        assertEquals(OpenUriStatus.OPENED, out.status)
        val intent = launcher.lastIntent
        assertTrue("the launcher must be invoked for a valid http(s) url", intent != null)
        assertEquals(Intent.ACTION_VIEW, intent!!.action)
        assertEquals("https://example.com/some/path?x=1", intent.dataString)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
    }

    @Test
    fun openUriRefusesNonHttpSchemesWithoutLaunching() {
        for (bad in listOf("file:///etc/passwd", "javascript:alert(1)", "market://details?id=x", "tel:12345")) {
            launcher.lastIntent = null
            val out = bridge.openUri(bad)
            assertEquals(OpenUriStatus.REFUSED, out.status)
            assertEquals("scheme", out.reason)
            assertNull(
                "no intent may be built or launched for $bad",
                launcher.lastIntent,
            )
        }
    }

    // ── android.share: real ACTION_CHOOSER wrapping ACTION_SEND ────────────────────────────

    @Test
    fun shareBuildsAChooserWrappingActionSendWithTheTextAndSubject() {
        val out = bridge.share("hello world", "greeting")
        assertEquals(ShareStatus.SHARED, out.status)
        val chooser = launcher.lastIntent
        assertTrue("the launcher must be invoked for a share", chooser != null)
        assertEquals(Intent.ACTION_CHOOSER, chooser!!.action)

        @Suppress("DEPRECATION") // single-arg getParcelableExtra is the API-29-compatible read
        val inner = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertTrue("the chooser must wrap a send intent", inner != null)
        assertEquals(Intent.ACTION_SEND, inner!!.action)
        assertEquals("text/plain", inner.type)
        assertEquals("hello world", inner.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("greeting", inner.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    @Test
    fun shareOmitsTheSubjectExtraWhenBlank() {
        bridge.share("hello world", "")
        @Suppress("DEPRECATION")
        val inner = launcher.lastIntent!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("hello world", inner.getStringExtra(Intent.EXTRA_TEXT))
        assertNull("a blank subject must not be sent as an extra", inner.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    // ── the real production foreground probe runs on-device without throwing ──────────────

    @Test
    fun theRealForegroundProbeReturnsAStableBooleanWithoutThrowing() {
        val real = ActivityManagerForegroundProbe(context)
        // The value depends on the live process importance during the test; the contract under
        // test is that it returns a Boolean deterministically within the window, never throwing.
        val first = real.isForeground()
        val second = real.isForeground()
        assertEquals(first, second)
    }
}

/** A controllable [ForegroundProbe] so the gate is exercised in both states without flakiness. */
private class ToggleProbe : ForegroundProbe {
    var fg = true

    override fun isForeground(): Boolean = fg
}

/** Records the launched [Intent] instead of firing it (the production [ContextIntentLauncher] does). */
private class RecordingLauncher : IntentLauncher {
    var lastIntent: Intent? = null
    var result: LaunchResult = LaunchResult.LAUNCHED

    override fun launch(intent: Intent): LaunchResult {
        lastIntent = intent
        return result
    }
}
