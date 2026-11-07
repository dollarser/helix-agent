package com.helix.feature.conversation.presentation

import com.helix.core.model.SessionPermissionMode
import com.helix.feature.conversation.R

/** The localized label for a mode. Tool/schema/enum names are never translated. */
fun SessionPermissionMode.labelRes(): Int =
    when (this) {
        SessionPermissionMode.APPROVAL_REQUIRED -> R.string.settings_perm_mode_approval_required
        SessionPermissionMode.WORKSPACE_TRUSTED -> R.string.settings_perm_mode_workspace_trusted
        SessionPermissionMode.FULL_ACCESS -> R.string.settings_perm_mode_full_access
        SessionPermissionMode.READ_ONLY -> R.string.settings_perm_mode_read_only
        SessionPermissionMode.CUSTOM -> R.string.settings_perm_mode_custom
    }

/** The preset modes — the valid new-session defaults (CUSTOM is not a default). Shared with the
 * CUSTOM editor: a custom draft is copied from one of these presets (never from CUSTOM). */
val SESSION_PERMISSION_PRESETS =
    listOf(
        SessionPermissionMode.APPROVAL_REQUIRED,
        SessionPermissionMode.WORKSPACE_TRUSTED,
        SessionPermissionMode.FULL_ACCESS,
        SessionPermissionMode.READ_ONLY,
    )
