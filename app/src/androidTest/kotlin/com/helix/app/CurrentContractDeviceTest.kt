package com.helix.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.mcp.ToolDiscoveryWorkset
import com.helix.core.model.AgentMode
import com.helix.core.model.GoalBudgets
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.TurnBudgets
import com.helix.core.storage.repository.SessionRunControlRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Synthetic state only; the runner may stop the process between setup and verify. */
class CurrentBudgetDeviceTest {
    @Test fun savedOutputAndContinuationBudgetsReloadFromPreferencesAndRoom() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val c = app.appContainer
        val phase = InstrumentationRegistry.getArguments().getString("budgetPhase") ?: "roundtrip"
        require(phase in setOf("setup", "verify", "roundtrip"))
        val marker = File(app.noBackupFilesDir, "budget-restart-fixture")
        val session = "current-budget-restart-fixture"
        val output = TurnBudgets(maxOutputTokens = 2048)
        val goal = GoalBudgets(maxGoalRounds = 7)
        if (phase != "verify") {
            check(!marker.exists() && c.storage.sessions.find(session) == null)
            val original = c.runControlStore.current
            marker.writeText(original.budgets.toStorageString() + "\n" + original.goalBudgets.toStorageString())
            c.runControlStore.setBudgets(output)
            c.runControlStore.setGoalBudgets(goal)
            c.storage.sessions.create(session, "Synthetic budget restart", null, null, 0)
            c.storage.sessionRunControls.setForSession(
                session,
                SessionRunControlRecord(AgentMode.ACT, false, output, ReasoningEffort.OFF, goal),
                0,
            )
        }
        if (phase != "setup") {
            check(marker.exists())
            try {
                assertEquals(output, c.runControlStore.current.budgets)
                assertEquals(goal, c.runControlStore.current.goalBudgets)
                val saved = requireNotNull(c.storage.sessionRunControls.forSession(session))
                assertEquals(output, saved.budgets)
                assertEquals(goal, saved.goalBudgets)
            } finally {
                val original = marker.readLines()
                c.runControlStore.setBudgets(TurnBudgets.parse(original[0]))
                c.runControlStore.setGoalBudgets(GoalBudgets.parse(original[1]))
                c.storage.deleteSessionPermanently(session)
                check(marker.delete())
            }
        }
    }
}

class CurrentDiscoveryDeviceTest {
    @Test fun productionContributionsExpandOnlyPresentSameOriginCompanions() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val c = app.appContainer
        val all = c.toolPipeline.registry.all()
        val developer = app.packageName.endsWith(".developer")
        if (!developer) {
            assertFalse(all.any { it.name.value.startsWith("ui.") || it.name.value.startsWith("code.linux.") })
        }
        val names =
            listOf("browser.click", "documents.read") +
                if (developer) listOf("ui.screenshot", "code.linux.job.start") else emptyList()
        for (name in names) {
            val descriptor = requireNotNull(all.singleOrNull { it.name.value == name }) { "Missing tool: $name" }
            assertTrue("Missing declarations: $name", descriptor.discoveryCompanions.isNotEmpty())
            val expanded = ToolDiscoveryWorkset.complete(listOf(descriptor), all)
            assertTrue(expanded.all { it.origin == descriptor.origin })
            assertTrue(expanded.map { it.name }.containsAll(descriptor.discoveryCompanions))
            val revoked = descriptor.discoveryCompanions.first()
            val filtered = ToolDiscoveryWorkset.complete(listOf(descriptor), all.filterNot { it.name == revoked })
            assertFalse(filtered.any { it.name == revoked })
        }
    }
}
