package com.helix.app.ui

import com.helix.app.R
import com.helix.app.account.AccountDataFailure
import com.helix.app.account.AccountRequestFailure
import com.helix.core.model.ModelErrorCode
import com.helix.provider.api.wire.mapHttpStatus
import java.io.IOException
import java.net.SocketTimeoutException

/** Localized fallback labels; parsed HTTP diagnostics are separately rendered by AccountFailureNotice. */
internal fun accountFailureLabel(error: Exception): Int =
    when (error) {
        is AccountRequestFailure -> {
            when (error.details.errorCode) {
                "provider_auth_failed", "provider_credit_exhausted", "provider_region_restricted",
                "global_cloud_disabled", "global_budget_exhausted",
                -> R.string.account_error_service

                "password_change_required" -> R.string.account_initial_password

                "email_verification_required", "email_verification_invalid" -> R.string.account_code_invalid

                "email_in_use", "email_already_verified", "email_binding_changed" -> R.string.account_binding_conflict

                "email_delivery_unavailable", "registration_disabled" -> R.string.account_registration_unavailable

                else -> accountRequestFailureLabel(error.status)
            }
        }

        is AccountDataFailure -> {
            R.string.account_failure
        }

        is SocketTimeoutException -> {
            R.string.account_error_timeout
        }

        is IOException -> {
            R.string.account_error_connection
        }

        else -> {
            R.string.account_failure
        }
    }

private fun accountRequestFailureLabel(status: Int): Int =
    if (status in 300..399 || status in setOf(404, 405, 410, 415)) {
        R.string.account_error_endpoint
    } else {
        when (mapHttpStatus(status).first) {
            ModelErrorCode.AUTH -> R.string.account_error_auth
            ModelErrorCode.RATE_LIMITED -> R.string.account_error_rate_limited
            ModelErrorCode.SERVER_ERROR -> R.string.account_error_service
            ModelErrorCode.TIMEOUT -> R.string.account_error_timeout
            else -> R.string.account_error_request
        }
    }
