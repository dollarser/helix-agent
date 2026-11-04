package com.helix.extensions.mobileuse.config

import com.helix.core.policy.UserScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileUseGrantStoreTest {
    @Test fun initialModeUsesAvailableBackendOnceAndNeverSilentlyFallsBackAfterConnectionLoss() {
        val lines = mutableMapOf<String, List<String>>()
        var shell = true
        val store =
            MobileUseGrantStore(
                { lines[it].orEmpty() },
                { k, v -> lines[k] = v },
                { "selected" },
                defaultBackground = { shell },
            )
        assertEquals(true to false, store.executionSettings())
        store.configureGlobal(setOf("fixture.app"), false)
        shell = false
        assertTrue(requireNotNull(store.find("a")).backgroundDisplay)
        store.configureExecution(false, false)
        assertFalse(requireNotNull(store.find("a")).backgroundDisplay)
        val accessibilityOnly =
            MobileUseGrantStore(
                { emptyList() },
                { _, _ -> },
                { null },
                defaultBackground = { false },
            )
        assertEquals(false to false, accessibilityOnly.executionSettings())
    }

    private val records = mutableMapOf<String, List<String>>()
    private val selections = mutableMapOf<String, String>()
    private var sequence = 0

    private fun store() =
        MobileUseGrantStore(
            { records[it].orEmpty() },
            { k, v -> records[k] = v },
            selections::get,
            { "config-${++sequence}" },
        )

    @Test fun displayChoiceInvalidatesOldProofsWithoutChangingUserApplicationScope() {
        val store = store()
        store.configureGlobal(setOf("fixture.app"), false)
        selections["a"] = "selection-a"
        val main = requireNotNull(store.find("a"))
        store.configureExecution(background = true, allowMoveFromMain = false)
        val background = requireNotNull(store.find("a"))
        assertTrue(background.backgroundDisplay)
        assertFalse(background.allowMoveFromMain)
        assertEquals(main.scope.allowedPackages, background.scope.allowedPackages)
        assertFalse(store.matches("a", main.scope.toScopeRef()))
        store.configureExecution(background = true, allowMoveFromMain = true)
        assertFalse(store.matches("a", background.scope.toScopeRef()))
        assertTrue(requireNotNull(store.find("a")).allowMoveFromMain)
    }

    @Test fun malformedExecutionSettingsCannotBecomeAMainScreenFallback() {
        val store = store()
        store.configureGlobal(emptySet(), true)
        records[MobileUseGrantStore.EXECUTION_KEY] = listOf("unknown", "false")
        assertThrows(MobileUseConfigurationUnreadable::class.java) { store.globalConfiguration() }
    }

    @Test fun globalEditsInvalidateSelectedConversationProofsWithoutCopyingConfiguration() {
        val store = store()
        store.configureGlobal(emptySet(), true)
        assertNull(store.find("a"))
        selections["a"] = "selection-a"
        selections["b"] = "selection-b"
        val first = store.find("a")!!
        assertNotEquals(first.scope.grantId, store.find("b")!!.scope.grantId)
        store.configureGlobal(setOf("com.allowed"), false)
        assertFalse(store.matches("a", first.scope.toScopeRef()))
        assertEquals(setOf("com.allowed"), store.find("a")!!.scope.allowedPackages)
        assertNull(store.find("c"))
        assertEquals(setOf(MobileUseGrantStore.CONFIG_KEY, MobileUseGrantStore.EXECUTION_KEY), records.keys)
    }

    @Test fun failedGlobalEditFailsClosedAndCanBeRetried() {
        var fail = false
        val store =
            MobileUseGrantStore({ records[it].orEmpty() }, { k, v ->
                check(!fail)
                records[k] = v
            }, selections::get)
        store.configureGlobal(emptySet(), true)
        selections["a"] = "selection"
        fail = true
        assertThrows(IllegalStateException::class.java) { store.configureGlobal(setOf("com.allowed"), false) }
        assertNull(store.find("a"))
        fail = false
        store.configureGlobal(setOf("com.allowed"), false)
        assertEquals(setOf("com.allowed"), store.find("a")!!.scope.allowedPackages)
    }

    @Test fun recreationDerivesTheSameScopeFromDurableSelectionAndConfiguration() {
        store().configureGlobal(setOf("com.example.App"), false)
        selections["a"] = "selection"
        val grant = store().find("a")
        repeat(3) { assertEquals(grant, store().find("a")) }
        assertNull(store().find("b"))
        assertFalse(store().matches("b", grant!!.scope.toScopeRef()))
    }

    @Test fun identicalSavePreservesIdentityButScopeAndSharingChangesInvalidateIt() {
        val store = store()
        val first = store.configureGlobal(setOf("com.app.one"), false)
        assertEquals(first, store.configureGlobal(setOf("com.app.one"), false))
        val expanded = store.configureGlobal(emptySet(), true)
        assertNotEquals(first.scope.grantId, expanded.scope.grantId)
        assertNotEquals(expanded.scope.grantId, store.configureGlobal(emptySet(), true, false).scope.grantId)
    }

    @Test fun disableAndReenableCannotResurrectOldProofOrAffectAnotherConversation() {
        val store = store()
        store.configureGlobal(emptySet(), true)
        selections["a"] = "first"
        selections["b"] = "other"
        val first = store.find("a")!!
        val other = store.find("b")
        selections.remove("a")
        assertNull(store.find("a"))
        selections["a"] = "second"
        assertFalse(store.matches("a", first.scope.toScopeRef()))
        assertEquals(other, store.find("b"))
    }

    @Test fun fullAppSelectionSurvivesPersistenceWithoutScopeRefTruncation() {
        val packages = (1..1000).map { "com.example.application$it" }.toSet()
        store().configureGlobal(packages, false)
        selections["a"] = "selection"
        val grant = store().find("a")!!
        assertEquals(packages, grant.scope.allowedPackages)
        assertTrue(grant.scope.toScopeRef().length <= UserScope.MAX_SCOPE_REF_LENGTH)
        assertFalse(grant.scope.permitsPackage("com.unselected.app"))
    }

    @Test fun malformedConfigurationNeverProducesAuthorization() {
        records[MobileUseGrantStore.CONFIG_KEY] = listOf("invalid")
        selections["a"] = "selection"
        assertThrows(IllegalStateException::class.java) { store().find("a") }
    }

    @Test fun unavailableSelectionStoreNeverFallsBackToSavedConfiguration() {
        store().configureGlobal(emptySet(), true)
        val store =
            MobileUseGrantStore(
                { records[it].orEmpty() },
                { _, _ -> error("no write") },
                { error("storage unavailable") },
            )
        assertThrows(IllegalStateException::class.java) { store.find("a") }
    }

    @Test fun unsuccessfulPersistenceIsNotReportedAsSaved() {
        val store = MobileUseGrantStore({ emptyList() }, { _, _ -> error("Storage unavailable") }, { "selection" })
        assertThrows(IllegalStateException::class.java) { store.configureGlobal(emptySet(), true) }
        assertNull(store.find("a"))
    }
}
