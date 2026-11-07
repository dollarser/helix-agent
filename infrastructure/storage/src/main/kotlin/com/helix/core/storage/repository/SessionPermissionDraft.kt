package com.helix.core.storage.repository

import com.helix.core.model.OperationEffect
import com.helix.core.model.OperationRule
import com.helix.core.model.SessionPermissionMode

/**
 * The read projection of one session's CUSTOM permission draft (HXA-209 D, ADR-PERMISSIONS-001
 * section 4): the copied-from preset, the copied-then-edited rule snapshot and the user change
 * time. Returned by [SessionPermissionConfigRepository.customDraftFor]; the settings UI reads it
 * to populate the custom editor and to restore the draft when CUSTOM is re-selected.
 *
 * This is a UI/provenance projection — the dispatcher's live read
 * ([SessionPermissionConfigRepository.forSession]) never sees it, so it is not part of the
 * execution linearization (it has no [revision]).
 */
data class SessionPermissionDraft(
    val sourcePreset: SessionPermissionMode,
    val rules: Map<OperationEffect, OperationRule>,
    val configVersion: Int,
    val updatedAtEpoch: Long,
)
