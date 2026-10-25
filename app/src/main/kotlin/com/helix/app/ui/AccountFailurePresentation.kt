package com.helix.app.ui

import com.helix.app.R
import com.helix.app.account.AccountDataFailure
import com.helix.app.account.AccountRequestFailure
import com.helix.core.model.ModelErrorCode
import com.helix.provider.api.wire.mapHttpStatus
import java.io.IOException
import java.net.SocketTimeoutException

/** Only closed localized labels cross the UI boundary; never exception messages or response bodies. */
internal fun accountFailureLabel(error: Exception): Int =
    when (error) {
        is AccountRequestFailure -> {
            when (mapHttpStatus(error.status).first) {
                ModelErrorCode.AUTH -> R.string.account_error_auth
                ModelErrorCode.RATE_LIMITED -> R.string.account_error_rate_limited
                ModelErrorCode.SERVER_ERROR -> R.string.account_error_service
                ModelErrorCode.TIMEOUT -> R.string.account_error_timeout
                else -> R.string.account_error_request
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
