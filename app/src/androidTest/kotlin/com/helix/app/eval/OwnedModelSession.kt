package com.helix.app.eval

import com.helix.core.agent.GoalBudgetDefaults
import com.helix.core.model.AgentMode
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.TurnBudgets
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.SessionRunControlRecord
import com.helix.feature.conversation.application.ChatService
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Seed only the owned synthetic session; this model fixture does not test composer command scheduling. */
internal suspend fun configureOwnedModelSession(
    storage: HelixStorage,
    chat: ChatService,
    session: String,
    mode: AgentMode,
    timing: (String, Long) -> Unit = { _, _ -> },
) {
    val seedStarted = android.os.SystemClock.elapsedRealtime()
    val record = SessionRunControlRecord(mode, true, TurnBudgets(4096), ReasoningEffort.OFF, GoalBudgetDefaults.VALUE)
    storage.sessionRunControls.setForSession(session, record, System.currentTimeMillis())
    timing("run_control_persist_ms", android.os.SystemClock.elapsedRealtime() - seedStarted)
    val switchStarted = android.os.SystemClock.elapsedRealtime()
    chat.openSession(session)
    withTimeout(10_000) {
        while (true) {
            val screenReady = chat.screen.value.openSessionId == session && !chat.screen.value.preparingDraft
            val controlReady = chat.runControl.value.mode == mode && chat.runControl.value.budgets == record.budgets
            if (screenReady && controlReady) break
            delay(20)
        }
    }
    check(storage.sessionRunControls.forSession(session) == record)
    timing("session_switch_ready_ms", android.os.SystemClock.elapsedRealtime() - switchStarted)
}
