package com.helix.app.provider

import com.helix.app.R
import com.helix.core.model.ModelErrorCode
import com.helix.feature.conversation.presentation.ProviderBindingIssue

internal fun providerBindingIssue(
    exists: Boolean,
    changed: Boolean,
    status: ConnectionTestStatus,
    account: ManagedAccountSnapshot? = null,
    modelFailure: ModelErrorCode? = null,
): ProviderBindingIssue? {
    val failure = modelFailure ?: (status as? ConnectionTestStatus.Failed)?.code
    return when {
        !exists -> {
            ProviderBindingIssue.SOURCE_DELETED
        }

        account != null && !account.ready -> {
            when (account.state) {
                ManagedAccountSnapshot.State.UNAVAILABLE -> ProviderBindingIssue.NETWORK
                else -> ProviderBindingIssue.AUTHENTICATION
            }
        }

        failure != null -> {
            bindingFailure(failure)
        }

        status is ConnectionTestStatus.Passed -> {
            null
        }

        changed -> {
            ProviderBindingIssue.CONFIGURATION_CHANGED
        }

        else -> {
            ProviderBindingIssue.UNTESTED
        }
    }
}

private fun bindingFailure(code: ModelErrorCode): ProviderBindingIssue =
    when (code) {
        ModelErrorCode.AUTH -> ProviderBindingIssue.AUTHENTICATION
        ModelErrorCode.TRANSPORT, ModelErrorCode.TIMEOUT -> ProviderBindingIssue.NETWORK
        else -> ProviderBindingIssue.TEST_FAILED
    }

/** A successful fresh account directory can retire a model; a failed/stale fetch cannot. */
internal fun builtInBindingIssue(
    accountAvailable: Boolean,
    catalog: BuiltInModelCatalog,
    model: String,
    now: Long,
): ProviderBindingIssue? =
    when {
        !accountAvailable -> {
            ProviderBindingIssue.AUTHENTICATION
        }

        catalog.failure == null && catalog.fresh(now) && model !in catalog.models -> {
            ProviderBindingIssue.MODEL_MISSING
        }

        catalog.purposes[model].isNullOrEmpty() -> {
            catalog.failure?.let(::bindingFailure) ?: ProviderBindingIssue.MODEL_TASK_UNKNOWN
        }

        else -> {
            null
        }
    }
