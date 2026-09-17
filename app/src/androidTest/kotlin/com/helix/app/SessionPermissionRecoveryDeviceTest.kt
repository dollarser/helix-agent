package com.helix.app

import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.model.SessionPermissionMode
import com.helix.core.policy.SessionPermissionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HXA-209 D8 device acceptance: the session authorization is Room-persisted and survives a REAL
 * process death (not a re-seed). The owned runner (scripts/debug/2026-09-16/hxa209-d8) drives the
 * two-phase protocol: phase `setup` persists a stored config + a no-config session, writes this
 * process's pid to the durable `no_backup/recovery-device-pid` marker, and kills the process;
 * phase `verify` (a fresh process, installed once so the data is not uninstalled between phases)
 * asserts the pid actually changed, the stored config survived, and the no-config session was NOT
 * spuriously materialized. Run with no phase argument it is a normal in-process regression.
 *
 * Recovery activates nothing: a restart neither re-derives a stored config nor invents one for a
 * session that never stored a config.
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
    fun sessionAuthorizationSurvivesARealProcessRestart() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val container = (context as HelixApplication).appContainer
        val marker = context.noBackupFilesDir.resolve("recovery-device-pid")
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
                SessionPermissionConfig.of(SessionPermissionMode.WORKSPACE),
                now,
            )
            // The durable identity of THIS process, readable after the kill via run-as.
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
                SessionPermissionMode.WORKSPACE,
                container.sessionPermissionEdit.activeConfigFor(storedSession)?.mode,
            )
            // A session that never stored a config is still row-less: recovery did not invent one.
            assertNull(
                "recovery must not materialize a stored config for a bare session",
                container.sessionPermissionEdit.activeConfigFor(bareSession),
            )
        } finally {
            if (container.sessionPermissionEdit.activeConfigFor(storedSession) != null) {
                container.sessionPermissionEdit.resetSessionToDefault(storedSession, System.currentTimeMillis())
            }
            marker.delete()
        }
    }
}
