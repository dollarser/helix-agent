package com.helix.app.capability

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.model.Capability
import com.helix.core.policy.CapabilityCenter
import com.helix.core.policy.GrantState
import com.helix.core.storage.HelixStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/**
 * Device acceptance for the Capability Center (verification matrix HXA-032,
 * `:app:connectedConsumerDebugAndroidTest`): the production resolver must mirror the real system
 * state (doc 9 section 2), report honestly-unavailable Root/Accessibility (doc 9 section 6.2),
 * and the center must re-check on every call — revoking a permission mid-test is visible
 * immediately, the recorded GRANTED rows never substitute for the execution-time check
 * (缓存不代替执行时检查).
 *
 * Permission assertions compare the resolver against the platform's own state read directly in
 * the test (different code path), so they are stable regardless of prior test history on the
 * emulator. The live-change test drives a GRANT through UiAutomation (safe) and leaves
 * POST_NOTIFICATIONS granted (GoalReminderTest depends on it); it never revokes: Android kills
 * the process of a visible app whose runtime permission is revoked (logcat: "Killing
 * <pid>:com.helix.agent (adj 0): permissions revoked"), and the instrumentation runs under the
 * app's uid — revocation-direction visibility is covered by the JVM test
 * recordedGrantNeverSubstitutesForExecutionTimeCheck in :core:policy:test.
 *
 * The audit recorder writes to an ISOLATED database (ProcessRecoveryTest pattern): in a full
 * suite run the app process may still be resident after the Espresso tests and holds the shared
 * Room database, which a second process must never open.
 */
@RunWith(AndroidJUnit4::class)
class SystemCapabilityResolverTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val resolver = SystemCapabilityResolver(context)
    private val storage: HelixStorage by lazy {
        HelixStorage.open(
            context,
            "capability-center-test.db",
            File(context.filesDir, "helix-content-capability-test"),
        )
    }
    private val center = CapabilityCenter(resolver, StorageCapabilityGrantRecorder(storage))

    @Test
    fun everyCapabilityResolvesToATypedSystemGrant() {
        val start = Instant.now()
        Capability.entries.forEach { capability ->
            val grant = resolver.resolve(capability)
            assertEquals(capability, grant.capability)
            assertTrue("grantedBySystem must be true: $capability", grant.grantedBySystem)
            assertTrue(
                "checkedAt must be fresh (got ${grant.checkedAt})",
                !grant.checkedAt.isBefore(start.minusSeconds(1)) &&
                    !grant.checkedAt.isAfter(Instant.now().plusSeconds(5)),
            )
            assertTrue(grant.userScope == null)
        }
    }

    @Test
    fun webBrowsingMatchesSystemGroundTruth() {
        val expected =
            if (WebView.getCurrentWebViewPackage() != null) {
                GrantState.GRANTED
            } else {
                GrantState.UNAVAILABLE
            }
        assertEquals(expected, resolver.resolve(Capability.WEB_BROWSING).state)
        // the emulator image ships a system WebView
        assertEquals(GrantState.GRANTED, resolver.resolve(Capability.WEB_BROWSING).state)
    }

    @Test
    fun safDocumentTreeIsAvailableWithoutAScope() {
        val grant = resolver.resolve(Capability.SAF_DOCUMENT_TREE)
        assertEquals(GrantState.GRANTED, grant.state)
        assertTrue(grant.userScope == null)
    }

    @Test
    fun manageAllFilesMatchesSystemGroundTruth() {
        val expected =
            if (Build.VERSION.SDK_INT < 30) {
                GrantState.UNAVAILABLE
            } else if (Environment.isExternalStorageManager()) {
                GrantState.GRANTED
            } else {
                GrantState.DENIED
            }
        assertEquals(expected, resolver.resolve(Capability.MANAGE_ALL_FILES).state)
    }

    @Test
    fun notificationReadTracksLiveGrantThroughTheCenter() {
        if (Build.VERSION.SDK_INT < 33) {
            // legacy path: always granted, nothing to grant
            assertEquals(GrantState.GRANTED, center.check(Capability.NOTIFICATION_READ).state)
            return
        }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val packageName = context.packageName

        // baseline: the center must mirror whatever the system currently says
        val systemSaysGranted =
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        val baseline = center.check(Capability.NOTIFICATION_READ).state
        assertEquals(if (systemSaysGranted) GrantState.GRANTED else GrantState.DENIED, baseline)

        // a live state change must be visible on the very next check — the audit rows written by
        // the baseline check must never be served (缓存不代替执行时检查)
        automation.grantRuntimePermission(packageName, Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(GrantState.GRANTED, center.check(Capability.NOTIFICATION_READ).state)

        // leave granted: GoalReminderTest (HXA-013) depends on it
    }

    @Test
    fun calendarWriteMatchesSystemGroundTruth() {
        val expected =
            if (context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                GrantState.GRANTED
            } else {
                GrantState.DENIED
            }
        assertEquals(expected, resolver.resolve(Capability.CALENDAR_WRITE).state)
    }

    @Test
    fun rootAndAccessibilityAreHonestlyUnavailableInThisBuild() {
        // no libsu integration until the HXA-094 gate; no accessibility service component yet
        assertEquals(GrantState.UNAVAILABLE, resolver.resolve(Capability.ROOT_SHELL).state)
        assertEquals(GrantState.UNAVAILABLE, resolver.resolve(Capability.ACCESSIBILITY_AUTOMATION).state)
    }

    @Test
    fun repeatedResolvesAreStableForUnchangedSystemState() {
        val first = resolver.resolve(Capability.MANAGE_ALL_FILES).state
        val second = resolver.resolve(Capability.MANAGE_ALL_FILES).state
        assertEquals(first, second)
    }

    @Test
    fun auditRecorderWritesCapabilityGrantRows() {
        val grant = center.check(Capability.SAF_DOCUMENT_TREE)
        val rows = storage.capabilityGrants.listByType(Capability.SAF_DOCUMENT_TREE.name)
        assertTrue("audit rows must exist", rows.isNotEmpty())
        assertTrue(
            "a matching audit row must exist (state=${grant.state.name}, at=${grant.checkedAt})",
            rows.any {
                it.systemState == grant.state.name &&
                    it.userScopeRef == "none" &&
                    it.checkedAt == grant.checkedAt.toEpochMilli()
            },
        )
    }
}
