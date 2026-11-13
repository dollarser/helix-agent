package com.helix.app

import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.model.SessionPermissionMode
import com.helix.core.policy.SessionPermissionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HXA-209 D8 device acceptance: the session authorization is Room-persisted and survives a REAL
 * process death (not a re-seed). The owned runner (scripts/debug/2026-09-16/hxa209-d8) drives the
 * two-phase protocol: phase `setup` persists a stored config + a no-config session, writes this
 * process's pid to the durable `no_backup/recovery-device-pid` marker, and kills the process;
 * phase `verify` asserts the pid changed and both explicit and creation-time snapshots survived.
 * Run with no phase argument it is a normal in-process regression.
 *
 * Recovery activates nothing and does not replace creation-time snapshots with new defaults.
 */
@RunWith(AndroidJUnit4::class)
class SessionPermissionRecoveryDeviceTest {
    /**
     * FIXED ids (not per-run): the setup and verify phases are separate `am instrument` runs, so
     * both must address the SAME session. The session row is unique enough to not collide with any
     * other fixture and the stored config is reset in the finally so runs do not leak.
     */
    private val storedSession = "sp-recovery-stored"
    private val bareSession = "sp-recovery-bare"

    @Test
    fun revokedSessionDoesNotRegainPermissionAfterSwitchAndRealProcessRestart() {
        val context = ApplicationProvider.getApplicationContext<HelixApplication>()
        val container = context.appContainer
        val first = "sp-revocation-first"
        val second = "sp-revocation-second"
        val marker = context.noBackupFilesDir.resolve("revoked-permission-pid")
        val phase = InstrumentationRegistry.getArguments().getString("recoveryPhase")
        if (phase != "verify") {
            val now = System.currentTimeMillis()
            listOf(first, second).forEach { id ->
                if (container.storage.sessions
                        .list()
                        .none { it.id == id }
                ) {
                    container.storage.sessions.create(id, "Revocation recovery", null, null, now)
                }
            }
            container.sessionPermissionEdit.saveSessionConfig(
                first,
                SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                now,
            )
            container.sessionPermissionEdit.saveSessionConfig(
                second,
                SessionPermissionConfig.of(SessionPermissionMode.WORKSPACE_TRUSTED),
                now,
            )
            container.sessionPermissionEdit.saveSessionConfig(
                first,
                SessionPermissionConfig.of(SessionPermissionMode.READ_ONLY),
                now + 1,
            )
            assertEquals(
                SessionPermissionMode.WORKSPACE_TRUSTED,
                container.sessionPermissionEdit.activeConfigFor(second)?.mode,
            )
            marker.writeText(Process.myPid().toString())
            if (phase == "setup") Process.killProcess(Process.myPid())
        }
        if (phase == "setup") return
        try {
            if (phase == "verify") assertNotEquals(marker.readText().toInt(), Process.myPid())
            assertEquals(SessionPermissionMode.READ_ONLY, container.sessionPermissionEdit.activeConfigFor(first)?.mode)
            assertEquals(
                SessionPermissionMode.WORKSPACE_TRUSTED,
                container.sessionPermissionEdit.activeConfigFor(second)?.mode,
            )
        } finally {
            listOf(first, second).forEach { container.storage.deleteSessionPermanently(it) }
            marker.delete()
        }
    }

    @Test
    fun sessionAuthorizationSurvivesARealProcessRestart() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val container = (context as HelixApplication).appContainer
        val marker = context.noBackupFilesDir.resolve("recovery-device-pid")
        val defaultMarker = context.noBackupFilesDir.resolve("recovery-permission-snapshot")
        val phase = InstrumentationRegistry.getArguments().getString("recoveryPhase")
        if (phase != "verify") {
            // Persist the state that must survive the restart.
            val now = System.currentTimeMillis()
            val sessions = container.storage.sessions
            if (sessions.list().none { it.id == storedSession }) {
                sessions.create(storedSession, "sp recovery stored", null, null, now)
            }
            if (sessions.list().none { it.id == bareSession }) {
                sessions.create(bareSession, "sp recovery bare", null, null, now)
            }
            container.sessionPermissionEdit.saveSessionConfig(
                storedSession,
                SessionPermissionConfig.of(SessionPermissionMode.WORKSPACE_TRUSTED),
                now,
            )
            // The durable identity of THIS process, readable after the kill via run-as.
            defaultMarker.writeText(
                requireNotNull(container.sessionPermissionEdit.activeConfigFor(bareSession)).mode.name,
            )
            marker.writeText(Process.myPid().toString())
            if (phase == "setup") {
                Process.killProcess(Process.myPid())
            }
        }
        if (phase == "setup") return
        try {
            if (phase == "verify") {
                assertNotEquals(
                    "the process must have actually restarted (different pid)",
                    marker.readText().toInt(),
                    Process.myPid(),
                )
            }
            // The stored config survived the restart (it was not re-seeded back to the default).
            assertEquals(
                SessionPermissionMode.WORKSPACE_TRUSTED,
                container.sessionPermissionEdit.activeConfigFor(storedSession)?.mode,
            )
            assertEquals(
                "creation-time permission snapshot survives recovery",
                defaultMarker.readText(),
                container.sessionPermissionEdit
                    .activeConfigFor(bareSession)
                    ?.mode
                    ?.name,
            )
        } finally {
            if (container.sessionPermissionEdit.activeConfigFor(storedSession) != null) {
                container.sessionPermissionEdit.resetSessionToDefault(storedSession, System.currentTimeMillis())
            }
            marker.delete()
            defaultMarker.delete()
        }
    }
}
