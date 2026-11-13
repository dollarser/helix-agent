package com.helix.app.provider

import com.helix.provider.api.ProviderConfig

/** Form-scoped identity and optimistic baseline. Contains aliases only, never credential material. */
class ProviderDraftSaveSession internal constructor(
    val providerId: String,
    internal val expectedConfig: ProviderConfig?,
    internal val expectedSelection: ProviderModelSelection,
) {
    internal var completed = false
    internal var recoveryRequired = false
}

class ProviderDraftSaveConflictException : IllegalStateException("Provider changed; reopen before saving")

class ProviderDraftSaveRecoveryRequiredException(
    cause: Throwable? = null,
) : IllegalStateException("Save could not be fully restored; reopen and review stored settings", cause)

/**
 * Compensating local writes, not a cross-store transaction. A failed compensation poisons this
 * form's session: it may not replay an uncertain create or overwrite a partially saved edit.
 * Caller serializes access and shields the admitted write/compensation from coroutine cancellation.
 * Compensation handles all storage failures and preserves their causes.
 */
@Suppress("TooGenericExceptionCaught", "ThrowsCount")
internal fun persistProviderDraft(
    session: ProviderDraftSaveSession,
    write: () -> Unit,
    restore: () -> Unit,
    verifyRestored: () -> Boolean,
) {
    if (session.recoveryRequired) throw ProviderDraftSaveRecoveryRequiredException()
    if (session.completed) return
    try {
        write()
        session.completed = true
    } catch (failure: Exception) {
        try {
            restore()
            check(verifyRestored()) { "Provider draft restoration could not be verified" }
        } catch (restoration: Exception) {
            session.recoveryRequired = true
            failure.addSuppressed(restoration)
            throw ProviderDraftSaveRecoveryRequiredException(failure)
        }
        throw failure
    }
}

/** Probes may refresh derived capabilities without changing any editable configuration field. */
internal fun sameProviderEditableConfig(
    actual: ProviderConfig?,
    expected: ProviderConfig?,
): Boolean =
    if (actual == null || expected == null) {
        actual == expected
    } else {
        actual.copy(capabilitySnapshot = expected.capabilitySnapshot) == expected
    }
