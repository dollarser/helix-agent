package com.helix.app.ui

import com.helix.app.R
import com.helix.app.approval.PreferenceContractChanged
import com.helix.app.approval.ToolApprovalSettingsModel
import kotlinx.coroutines.CancellationException

/** Cancellation remains cancellation; a write failure never becomes a success message. */
@Suppress("TooGenericExceptionCaught")
internal suspend fun savePreferenceNotice(write: suspend () -> ToolApprovalSettingsModel.Row?): Int =
    try {
        write()?.let { stateResOf(it.state) } ?: R.string.preference_save_unavailable
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: PreferenceContractChanged) {
        R.string.preference_save_changed
    } catch (_: Exception) {
        R.string.preference_save_failed
    }
