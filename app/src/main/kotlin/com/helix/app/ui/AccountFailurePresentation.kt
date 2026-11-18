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
            accountErrorLabels[error.details.errorCode] ?: accountRequestFailureLabel(error.status)
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

private val accountErrorLabels =
    mapOf(
        "provider_auth_failed" to R.string.account_error_service,
        "provider_credit_exhausted" to R.string.account_error_service,
        "provider_region_restricted" to R.string.account_error_service,
        "global_cloud_disabled" to R.string.account_error_service,
        "global_budget_exhausted" to R.string.account_error_service,
        "captcha_required" to R.string.account_captcha_retry,
        "captcha_invalid" to R.string.account_captcha_retry,
        "captcha_rate_limited" to R.string.account_error_rate_limited,
        "captcha_unavailable" to R.string.account_captcha_failed,
        "password_reset_invalid" to R.string.account_reset_code_invalid,
        "password_reset_code_required" to R.string.account_reset_code_invalid,
        "invalid_password" to R.string.account_recovery_password_rule,
        "invalid_email" to R.string.account_email_invalid,
        "invalid_reset_options" to R.string.account_error_request,
        "password_change_required" to R.string.account_initial_password,
        "email_verification_required" to R.string.account_code_invalid,
        "email_verification_invalid" to R.string.account_code_invalid,
        "email_in_use" to R.string.account_binding_conflict,
        "email_already_verified" to R.string.account_binding_conflict,
        "email_binding_changed" to R.string.account_binding_conflict,
        "email_delivery_unavailable" to R.string.account_code_unavailable,
        "display_name_invalid" to R.string.account_display_name_invalid,
        "display_name_change_limited" to R.string.account_display_name_limited,
        "registration_disabled" to R.string.account_registration_unavailable,
    )
