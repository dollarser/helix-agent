package com.helix.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.helix.app.approval.SessionPermissionEditService
import com.helix.core.model.SessionPermissionMode
import com.helix.extensions.skills.SkillRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class SessionSettingsSummary(
    val permission: SessionPermissionMode,
    val skills: Int,
)

/** Reload after editors close; a failed read never becomes an invented empty selection. */
@Composable
@Suppress("LongParameterList") // One projection over the existing session facades; no new settings authority.
internal fun rememberSessionSettingsSummary(
    sessionId: String?,
    draft: Boolean,
    editorOpen: Boolean,
    permissions: SessionPermissionEditService,
    skills: SkillRepository?,
): Pair<SessionSettingsSummary?, Boolean> {
    var summary by remember(sessionId) { mutableStateOf<SessionSettingsSummary?>(null) }
    var failed by remember(sessionId) { mutableStateOf(false) }
    LaunchedEffect(sessionId, draft, editorOpen) {
        if (editorOpen || sessionId == null) return@LaunchedEffect
        try {
            summary =
                withContext(Dispatchers.IO) {
                    SessionSettingsSummary(
                        (
                            if (draft) {
                                permissions.appDefault()
                            } else {
                                permissions.activeConfigFor(sessionId)
                                    ?: permissions.appDefault()
                            }
                        ).mode,
                        skills?.list(sessionId)?.count { it.enabled } ?: 0,
                    )
                }
            failed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            summary = null
            failed = true
        }
    }
    return summary to failed
}
