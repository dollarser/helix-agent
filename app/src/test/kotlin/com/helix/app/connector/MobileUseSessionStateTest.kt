package com.helix.app.connector

import com.helix.app.plugin.PluginSelectionUnavailable
import com.helix.app.plugin.PluginSessionRow
import com.helix.app.plugin.PluginSetupReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class MobileUseSessionStateTest {
    @Test fun unavailableBackendRequiresExplanationAndReturningDoesNotAutoEnable() =
        runBlocking {
            val fixture = Fixture(readyRow.copy(setupReason = PluginSetupReason.MOBILE_BACKEND, ready = false))
            val state = fixture.state()
            state.refresh()
            state.choose(true)
            assertEquals(PluginSetupReason.MOBILE_BACKEND, state.setupReason)
            assertEquals(0, fixture.writes.get())
            state.dismissSetup()
            state.refresh()
            assertFalse(state.active)
            assertEquals(0, fixture.writes.get())
            fixture.facts.set(readyRow)
            state.refresh()
            assertFalse(state.active)
            assertEquals(0, fixture.writes.get())
            state.choose(true)
            assertTrue(state.active)
            assertEquals(1, fixture.writes.get())
        }

    @Test fun scopeSaveAndSettingsReturnDoNotSelectAnyPlugin() =
        runBlocking {
            val fixture = Fixture(readyRow.copy(setupReason = PluginSetupReason.MOBILE_SCOPE))
            val state = fixture.state()
            state.refresh()
            state.choose(true)
            assertEquals(PluginSetupReason.MOBILE_SCOPE, state.setupReason)
            state.dismissSetup()
            fixture.facts.set(readyRow)
            state.refresh()
            assertFalse(state.active)
            assertEquals(0, fixture.writes.get())
        }

    @Test fun disabledProjectionDoesNotBlockTheApplicationServiceFromEnablingItsNativePlugin() =
        runBlocking {
            val disabled = readyRow.copy(enabled = false, ready = false, setupReason = PluginSetupReason.RELOAD)
            val fixture = Fixture(disabled)
            fixture.beforeWrite = { fixture.facts.set(readyRow) }
            val state = fixture.state()
            state.refresh()
            state.choose(true)
            assertTrue(state.active)
            assertEquals(null, state.setupReason)
            assertEquals(1, fixture.writes.get())
        }

    @Test fun permissionRevokedAfterProjectionIsCaughtByFreshApplicationGate() =
        runBlocking {
            val fixture = Fixture()
            val state = fixture.state()
            state.refresh()
            fixture.beforeWrite = { throw PluginSelectionUnavailable(PluginSetupReason.MOBILE_BACKEND) }
            state.choose(true)
            assertEquals(PluginSetupReason.MOBILE_BACKEND, state.setupReason)
            assertFalse(state.active)
            assertFalse(fixture.facts.get().selected)
        }

    @Test fun changedConversationExpiresOldTargetInsteadOfRetargetingIt() =
        runBlocking {
            val fixture = Fixture()
            val state = fixture.state()
            state.refresh()
            fixture.current.set(false)
            state.choose(true)
            assertTrue(state.expired)
            assertFalse(state.active)
            assertEquals(0, fixture.writes.get())
            state.refresh()
            assertEquals(0, fixture.writes.get())
        }

    @Test fun sessionChangeDuringReadCannotLeaveAnOldEnableIntent() =
        runBlocking {
            val fixture = Fixture()
            val state = fixture.state()
            state.refresh()
            fixture.beforeRead = { fixture.current.set(false) }
            state.choose(true)
            assertTrue(state.expired)
            assertEquals(0, fixture.writes.get())
            fixture.current.set(true)
            fixture.beforeRead = {}
            state.refresh()
            assertFalse(state.active)
            assertEquals(0, fixture.writes.get())
        }

    @Test fun rapidRepeatedEnableMakesOnlyOneScopedWrite() =
        runBlocking {
            val fixture = Fixture()
            val state = fixture.state()
            state.refresh()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            fixture.beforeWrite = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            val first = launch(Dispatchers.Default) { state.choose(true) }
            try {
                withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
                state.choose(true)
            } finally {
                release.countDown()
                first.join()
            }
            assertTrue(state.active)
            assertEquals(1, fixture.writes.get())
        }

    @Test fun failedWriteKeepsPageEditableAndCannotPublishAnEnabledState() =
        runBlocking {
            val fixture = Fixture()
            val state = fixture.state()
            state.refresh()
            fixture.beforeWrite = { throw IOException("synthetic failure") }
            state.choose(true)
            assertTrue(state.failed)
            assertFalse(state.active)
            assertFalse(state.busy)
            assertFalse(fixture.facts.get().selected)
            fixture.beforeWrite = {}
            state.choose(true)
            assertTrue(state.active)
        }

    @Test fun restoringNoticeOrResumingNeverReplaysSelection() =
        runBlocking {
            val fixture = Fixture(readyRow.copy(setupReason = PluginSetupReason.MOBILE_BACKEND))
            val restored = fixture.state(PluginSetupReason.MOBILE_BACKEND)
            restored.refresh()
            assertEquals(PluginSetupReason.MOBILE_BACKEND, restored.setupReason)
            assertEquals(0, fixture.writes.get())
            fixture.facts.set(readyRow)
            restored.refresh()
            assertEquals(null, restored.setupReason)
            assertFalse(restored.active)
            fixture.current.set(false)
            val stale = fixture.state(PluginSetupReason.MOBILE_BACKEND)
            stale.refresh()
            assertEquals(null, stale.setupReason)
            assertEquals(0, fixture.writes.get())
        }

    @Test fun anotherPluginCannotBeSelectedThroughMobileUsePage() =
        runBlocking {
            val fixture = Fixture(readyRow.copy(id = "other-plugin"))
            val state = fixture.state()
            state.refresh()
            assertTrue(state.failed)
            state.choose(true)
            assertEquals(0, fixture.writes.get())
            assertFalse(state.active)
        }

    @Test fun selectedPluginCanBeDisabledEvenAfterBackendAccessIsLost() =
        runBlocking {
            val fixture = Fixture(readyRow.copy(selected = true, setupReason = PluginSetupReason.MOBILE_BACKEND))
            val state = fixture.state()
            state.refresh()
            state.choose(false)
            assertFalse(state.active)
            assertFalse(fixture.facts.get().selected)
            assertEquals(1, fixture.writes.get())
        }

    @Test fun partialCapabilitiesAreNotMistakenForNeedingEveryBackendPermission() =
        runBlocking {
            val partial = readyRow.copy(selected = true, ready = false, readyComponents = 3, totalComponents = 5)
            val fixture = Fixture(partial)
            val state = fixture.state()
            state.refresh()
            assertTrue(state.active)
            assertEquals(0, fixture.writes.get())
        }

    @Test fun cancelledWriteIsNotReportedAsFailureOrAutomaticallyRepeated() =
        runBlocking {
            val fixture = Fixture()
            val state = fixture.state()
            state.refresh()
            fixture.beforeWrite = {
                fixture.facts.set(readyRow.copy(selected = true))
                throw CancellationException("synthetic committed cancellation")
            }
            try {
                state.choose(true)
                fail("Cancellation must propagate")
            } catch (_: CancellationException) {
                assertFalse(state.failed)
                assertFalse(state.busy)
            }
            fixture.beforeWrite = {}
            state.refresh()
            assertTrue(state.active)
            assertEquals(1, fixture.writes.get())
        }

    private class Fixture(
        initial: PluginSessionRow = readyRow,
    ) {
        val facts = AtomicReference(initial)
        val current = AtomicBoolean(true)
        val writes = AtomicInteger()
        var beforeRead: () -> Unit = {}
        var beforeWrite: () -> Unit = {}

        fun state(restored: PluginSetupReason? = null) =
            MobileUseSessionState(
                read = {
                    beforeRead()
                    facts.get()
                },
                select = { enabled ->
                    writes.incrementAndGet()
                    beforeWrite()
                    facts.set(facts.get().copy(selected = enabled))
                },
                isCurrent = current::get,
                restoredSetupReason = restored,
            )
    }

    companion object {
        private val readyRow = PluginSessionRow(MOBILE_USE_PLUGIN_ID, "Mobile Use", false, false, true, true)
    }
}
