package com.helix.app.automation

import androidx.compose.runtime.saveable.SaverScope
import com.helix.extensions.mobileuse.config.MobileUseGrantStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MobileUseSettingsStateTest {
    @Test fun incompatibleConfigurationOffersResetWithoutWritingOrEnlargingScope() =
        runBlocking {
            val lines = listOf("mobile-use-config-v2", "synthetic", "true")
            var writes = 0
            val invalid = MobileUseGrantStore({ lines }, { _, _ -> writes++ }, { null })
            val state = MobileUseSettingsState(invalid)
            state.load()
            assertTrue(state.requiresReset)
            assertTrue(state.failed)
            assertFalse(state.canSave)
            assertFalse(state.save())
            state.discard()
            assertTrue(state.failed)
            assertEquals(0, writes)
        }

    private val records = mutableMapOf<String, List<String>>()
    private val store = MobileUseGrantStore({ records[it].orEmpty() }, { key, value -> records[key] = value }, { null })

    @Test fun defaultIsDraftUntilSaved() =
        runBlocking {
            val state = MobileUseSettingsState(store)
            state.load()
            assertTrue(state.wholePhone)
            assertFalse(state.dirty)
            assertFalse(state.saved)
            assertNull(store.globalConfiguration())
            assertTrue(state.save())
            assertTrue(state.saved)
            assertTrue(store.globalConfiguration()!!.scope.allApplications)
            assertNull(store.find("conversation"))
        }

    @Test fun changingBackToOriginalScopeIsNotDirty() =
        runBlocking {
            store.configureGlobal(setOf("com.example.allowed"), false)
            val state = MobileUseSettingsState(store)
            state.load()
            state.wholePhone = true
            assertTrue(state.dirty)
            state.wholePhone = false
            assertFalse(state.dirty)
            state.packages = listOf("com.example.other")
            assertTrue(state.dirty)
            state.discard()
            assertEquals(listOf("com.example.allowed"), state.packages)
            assertTrue(state.saved)
        }

    @Test fun failedLoadCanRetryWithoutWriting() =
        runBlocking {
            var fail = true
            val source =
                MobileUseGrantStore({
                    if (fail) error("read failed") else emptyList()
                }, { _, _ -> error("unexpected write") }, { null })
            val state = MobileUseSettingsState(source)
            state.load()
            assertTrue(state.failed)
            assertFalse(state.canSave)
            fail = false
            state.load()
            assertTrue(state.loaded)
            assertFalse(state.failed)
            assertTrue(state.canSave)
        }

    @Test fun failedSavePreservesDraftAndCanRetry() =
        runBlocking {
            var fail = true
            val source =
                MobileUseGrantStore({ records[it].orEmpty() }, { key, value ->
                    if (fail) error("write failed")
                    records[key] = value
                }, { null })
            val state = MobileUseSettingsState(source)
            state.load()
            state.wholePhone = false
            state.packages = listOf("com.example.allowed")
            assertFalse(state.save())
            assertFalse(state.saved)
            assertTrue(state.dirty)
            assertEquals(listOf("com.example.allowed"), state.packages)
            fail = false
            assertTrue(state.save())
            assertTrue(state.saved)
        }

    @Test fun cancelledSaveRequiresAuthoritativeReload() =
        runBlocking {
            val source =
                MobileUseGrantStore({ emptyList() }, { _, _ -> throw CancellationException("cancelled") }, { null })
            val state = MobileUseSettingsState(source)
            state.load()
            try {
                state.save()
                fail("cancellation must propagate")
            } catch (_: CancellationException) {
                assertFalse(state.loaded)
                assertFalse(state.busy)
            }
        }

    @Test fun restorationPreservesDraftAndBaseline() =
        runBlocking {
            val state = MobileUseSettingsState(store)
            state.load()
            state.wholePhone = false
            state.packages = listOf("com.example.allowed")
            val saver = MobileUseSettingsState.saver(store)
            val saved = with(saver) { SaverScope { true }.save(state) }
            val restored = saver.restore(requireNotNull(saved))!!
            assertTrue(restored.dirty)
            assertEquals(state.packages, restored.packages)
            assertFalse(restored.wholePhone)
            assertNull(store.globalConfiguration())
        }
}
