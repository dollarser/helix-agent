package com.helix.app.reminder

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.helix.core.storage.reminder.LocalReminder

/** Editing a proposal is UI state; rotation must never schedule it or lose the chosen editor. */
internal class ReminderPageState : ViewModel() {
    var selected by mutableStateOf<LocalReminder?>(null)
    var editing by mutableStateOf(false)
    var interpreting by mutableStateOf(false)
    var scheduling by mutableStateOf(false)
    var receipt by mutableStateOf(ReminderService.CommitReceipt())
        private set

    fun openEditor(item: LocalReminder?) {
        receipt = ReminderService.CommitReceipt()
        selected = item
        editing = true
    }
}
