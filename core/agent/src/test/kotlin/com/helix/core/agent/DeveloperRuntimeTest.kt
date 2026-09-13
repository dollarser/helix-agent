package com.helix.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "Enable Developer Runtime" onboarding lifecycle (P1, research doc section 8, developer loop):
 * [DeveloperRuntime.reduce] over the capability states Not installed / Installing / Ready / Broken /
 * Repair / Update available. The happy path drives check → download → verify → install → self-test →
 * Ready; a failure at any step lands in Broken with a bounded detail; an illegal or out-of-order
 * event throws (fail-closed: an illegal transition is never a silent success).
 */
class DeveloperRuntimeTest {
    private fun reduce(
        state: DeveloperRuntimeState,
        event: DeveloperRuntimeEvent,
    ): DeveloperRuntimeState = DeveloperRuntime.reduce(state, event)

    private fun toReady(): DeveloperRuntimeState {
        var s = DeveloperRuntime.NOT_INSTALLED
        s = reduce(s, DeveloperRuntimeEvent.BeginInstall)
        s = reduce(s, DeveloperRuntimeEvent.EnvironmentChecked(true))
        s = reduce(s, DeveloperRuntimeEvent.Downloaded(true))
        s = reduce(s, DeveloperRuntimeEvent.Verified(true))
        s = reduce(s, DeveloperRuntimeEvent.Installed(true))
        return reduce(s, DeveloperRuntimeEvent.SelfTested(true))
    }

    private fun toSelfTest(): DeveloperRuntimeState {
        var s = DeveloperRuntime.NOT_INSTALLED
        s = reduce(s, DeveloperRuntimeEvent.BeginInstall)
        s = reduce(s, DeveloperRuntimeEvent.EnvironmentChecked(true))
        s = reduce(s, DeveloperRuntimeEvent.Downloaded(true))
        s = reduce(s, DeveloperRuntimeEvent.Verified(true))
        return reduce(s, DeveloperRuntimeEvent.Installed(true))
    }

    private fun assertIllegal(block: () -> Unit) {
        assertThrows(IllegalStateException::class.java, block)
    }

    @Test
    fun theInitialStateIsNotInstalledAndNotUsable() {
        val s = DeveloperRuntime.NOT_INSTALLED
        assertEquals(DeveloperRuntimeState.Status.NOT_INSTALLED, s.status)
        assertNull(s.step)
        assertNull(s.detail)
        assertFalse(s.isUsable)
        assertEquals("Not installed", s.progressLabel)
    }

    @Test
    fun theHappyPathReachesReady() {
        val s = toReady()
        assertEquals(DeveloperRuntimeState.Status.READY, s.status)
        assertNull(s.step)
        assertTrue(s.isUsable)
        assertEquals("Ready", s.progressLabel)
    }

    @Test
    fun onboardingTracksEachStepInOrder() {
        var s = reduce(DeveloperRuntime.NOT_INSTALLED, DeveloperRuntimeEvent.BeginInstall)
        assertEquals(DeveloperRuntimeState.Step.CHECK_ENVIRONMENT, s.step)
        assertEquals("Installing: Check environment", s.progressLabel)
        s = reduce(s, DeveloperRuntimeEvent.EnvironmentChecked(true))
        assertEquals(DeveloperRuntimeState.Step.DOWNLOAD, s.step)
        s = reduce(s, DeveloperRuntimeEvent.Downloaded(true))
        assertEquals(DeveloperRuntimeState.Step.VERIFY, s.step)
        s = reduce(s, DeveloperRuntimeEvent.Verified(true))
        assertEquals(DeveloperRuntimeState.Step.INSTALL, s.step)
        s = reduce(s, DeveloperRuntimeEvent.Installed(true))
        assertEquals(DeveloperRuntimeState.Step.SELF_TEST, s.step)
        assertEquals("Installing: Self test", s.progressLabel)
    }

    @Test
    fun anEnvironmentFailureBreaksBeforeAnyDownload() {
        val installed = reduce(DeveloperRuntime.NOT_INSTALLED, DeveloperRuntimeEvent.BeginInstall)
        val s = reduce(installed, DeveloperRuntimeEvent.EnvironmentChecked(false, "no network"))
        assertEquals(DeveloperRuntimeState.Status.BROKEN, s.status)
        assertEquals("no network", s.detail)
        assertFalse(s.isUsable)
    }

    @Test
    fun aDownloadFailureLeadsToBrokenWithTheDetail() {
        var s = reduce(DeveloperRuntime.NOT_INSTALLED, DeveloperRuntimeEvent.BeginInstall)
        s = reduce(s, DeveloperRuntimeEvent.EnvironmentChecked(true))
        val failed = reduce(s, DeveloperRuntimeEvent.Downloaded(false, "checksum mismatch"))
        assertEquals(DeveloperRuntimeState.Status.BROKEN, failed.status)
        assertEquals("checksum mismatch", failed.detail)
    }

    @Test
    fun aVerifyOrInstallFailureLeadsToBroken() {
        var s = reduce(DeveloperRuntime.NOT_INSTALLED, DeveloperRuntimeEvent.BeginInstall)
        s = reduce(s, DeveloperRuntimeEvent.EnvironmentChecked(true))
        s = reduce(s, DeveloperRuntimeEvent.Downloaded(true))
        assertEquals(DeveloperRuntimeState.Status.BROKEN, reduce(s, DeveloperRuntimeEvent.Verified(false)).status)

        s = reduce(DeveloperRuntime.NOT_INSTALLED, DeveloperRuntimeEvent.BeginInstall)
        s = reduce(s, DeveloperRuntimeEvent.EnvironmentChecked(true))
        s = reduce(s, DeveloperRuntimeEvent.Downloaded(true))
        s = reduce(s, DeveloperRuntimeEvent.Verified(true))
        assertEquals(DeveloperRuntimeState.Status.BROKEN, reduce(s, DeveloperRuntimeEvent.Installed(false)).status)
    }

    @Test
    fun aSelfTestFailureLeadsToBrokenAndCarriesItsDetail() {
        val s = reduce(toSelfTest(), DeveloperRuntimeEvent.SelfTested(false, "probe exited 1"))
        assertEquals(DeveloperRuntimeState.Status.BROKEN, s.status)
        assertEquals("probe exited 1", s.detail)
        assertFalse(s.isUsable)
    }

    @Test
    fun theBrokenDetailIsBounded() {
        val installed = reduce(DeveloperRuntime.NOT_INSTALLED, DeveloperRuntimeEvent.BeginInstall)
        val s = reduce(installed, DeveloperRuntimeEvent.EnvironmentChecked(false, "x".repeat(10_000)))
        val detail = requireNotNull(s.detail)
        assertEquals(513, detail.length)
        assertTrue(detail.endsWith("…"))
    }

    @Test
    fun updateAvailableFromReadyIsStillUsable() {
        val s = reduce(toReady(), DeveloperRuntimeEvent.UpdateAvailable)
        assertEquals(DeveloperRuntimeState.Status.UPDATE_AVAILABLE, s.status)
        assertTrue(s.isUsable)
        assertEquals("Update available", s.progressLabel)
    }

    @Test
    fun anIntegrityFailureDropsAReadyRuntimeToBroken() {
        val s = reduce(toReady(), DeveloperRuntimeEvent.IntegrityFailed("sha mismatch"))
        assertEquals(DeveloperRuntimeState.Status.BROKEN, s.status)
        assertEquals("sha mismatch", s.detail)
        assertFalse(s.isUsable)
    }

    @Test
    fun anIntegrityFailureAlsoBreaksAnUpdateAvailableRuntime() {
        val updateAvailable = reduce(toReady(), DeveloperRuntimeEvent.UpdateAvailable)
        val s = reduce(updateAvailable, DeveloperRuntimeEvent.IntegrityFailed("corrupt"))
        assertEquals(DeveloperRuntimeState.Status.BROKEN, s.status)
    }

    @Test
    fun aBrokenRuntimeCanBeRepairedBackToReady() {
        var s = reduce(toReady(), DeveloperRuntimeEvent.IntegrityFailed("drift"))
        s = reduce(s, DeveloperRuntimeEvent.BeginRepair)
        assertEquals(DeveloperRuntimeState.Status.REPAIRING, s.status)
        assertEquals(DeveloperRuntimeState.Step.CHECK_ENVIRONMENT, s.step)
        assertEquals("Repair: Check environment", s.progressLabel)
        s = reduce(s, DeveloperRuntimeEvent.EnvironmentChecked(true))
        s = reduce(s, DeveloperRuntimeEvent.Downloaded(true))
        s = reduce(s, DeveloperRuntimeEvent.Verified(true))
        s = reduce(s, DeveloperRuntimeEvent.Installed(true))
        s = reduce(s, DeveloperRuntimeEvent.SelfTested(true))
        assertEquals(DeveloperRuntimeState.Status.READY, s.status)
        assertTrue(s.isUsable)
    }

    @Test
    fun anUninstallReturnsToNotInstalledFromAnyState() {
        val ready = toReady()
        val updateAvailable = reduce(ready, DeveloperRuntimeEvent.UpdateAvailable)
        val broken = reduce(ready, DeveloperRuntimeEvent.IntegrityFailed("x"))
        val installing = reduce(DeveloperRuntime.NOT_INSTALLED, DeveloperRuntimeEvent.BeginInstall)
        val notInstalled = DeveloperRuntimeState.Status.NOT_INSTALLED
        assertEquals(notInstalled, reduce(ready, DeveloperRuntimeEvent.Uninstall).status)
        assertEquals(notInstalled, reduce(updateAvailable, DeveloperRuntimeEvent.Uninstall).status)
        assertEquals(notInstalled, reduce(broken, DeveloperRuntimeEvent.Uninstall).status)
        assertEquals(notInstalled, reduce(installing, DeveloperRuntimeEvent.Uninstall).status)
    }

    @Test
    fun beginInstallIsOnlyValidFromNotInstalledOrUpdateAvailable() {
        assertIllegal { reduce(toReady(), DeveloperRuntimeEvent.BeginInstall) }
        val updateAvailable = reduce(toReady(), DeveloperRuntimeEvent.UpdateAvailable)
        val started = reduce(updateAvailable, DeveloperRuntimeEvent.BeginInstall)
        assertEquals(DeveloperRuntimeState.Status.INSTALLING, started.status)
        assertEquals(DeveloperRuntimeState.Step.CHECK_ENVIRONMENT, started.step)
    }

    @Test
    fun beginRepairIsOnlyValidFromBroken() {
        assertIllegal { reduce(toReady(), DeveloperRuntimeEvent.BeginRepair) }
        val broken = reduce(toReady(), DeveloperRuntimeEvent.IntegrityFailed("x"))
        assertEquals(DeveloperRuntimeState.Status.REPAIRING, reduce(broken, DeveloperRuntimeEvent.BeginRepair).status)
    }

    @Test
    fun updateAvailableIsOnlyValidFromReady() {
        assertIllegal { reduce(DeveloperRuntime.NOT_INSTALLED, DeveloperRuntimeEvent.UpdateAvailable) }
    }

    @Test
    fun aStepOutcomeBeforeOnboardingStartsIsIllegal() {
        assertIllegal { reduce(DeveloperRuntime.NOT_INSTALLED, DeveloperRuntimeEvent.Downloaded(true)) }
        val installed = reduce(DeveloperRuntime.NOT_INSTALLED, DeveloperRuntimeEvent.BeginInstall)
        assertIllegal { reduce(installed, DeveloperRuntimeEvent.Downloaded(true)) }
    }

    @Test
    fun theStatusLabelsMatchTheCapabilityCenterRow() {
        assertEquals("Not installed", DeveloperRuntimeState.Status.NOT_INSTALLED.label)
        assertEquals("Installing", DeveloperRuntimeState.Status.INSTALLING.label)
        assertEquals("Ready", DeveloperRuntimeState.Status.READY.label)
        assertEquals("Broken", DeveloperRuntimeState.Status.BROKEN.label)
        assertEquals("Repair", DeveloperRuntimeState.Status.REPAIRING.label)
        assertEquals("Update available", DeveloperRuntimeState.Status.UPDATE_AVAILABLE.label)
    }

    @Test
    fun theStepLabelsMatchTheOnboardingFlow() {
        assertEquals("Check environment", DeveloperRuntimeState.Step.CHECK_ENVIRONMENT.label)
        assertEquals("Download", DeveloperRuntimeState.Step.DOWNLOAD.label)
        assertEquals("Verify", DeveloperRuntimeState.Step.VERIFY.label)
        assertEquals("Install", DeveloperRuntimeState.Step.INSTALL.label)
        assertEquals("Self test", DeveloperRuntimeState.Step.SELF_TEST.label)
    }
}
