package com.helix.app.ui

import com.helix.app.R
import com.helix.app.account.AccountDataFailure
import com.helix.app.account.AccountRequestFailure
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class AccountFailurePresentationTest {
    @Test fun httpFailuresNeverBecomeLocalConnectionHints() {
        for (status in listOf(500, 502, 503, 530)) {
            assertEquals(R.string.account_error_service, accountFailureLabel(AccountRequestFailure(status)))
        }
        for (status in listOf(401, 403)) {
            assertEquals(R.string.account_error_auth, accountFailureLabel(AccountRequestFailure(status)))
        }
        assertEquals(R.string.account_error_rate_limited, accountFailureLabel(AccountRequestFailure(429)))
        for (status in listOf(408, 504)) {
            assertEquals(R.string.account_error_timeout, accountFailureLabel(AccountRequestFailure(status)))
        }
        assertEquals(R.string.account_error_request, accountFailureLabel(AccountRequestFailure(422)))
    }

    @Test fun labelsDoNotDependOnSensitiveMessages() {
        assertEquals(R.string.account_error_connection, accountFailureLabel(IOException("sensitive response")))
        assertEquals(R.string.account_error_timeout, accountFailureLabel(SocketTimeoutException("secret")))
        assertEquals(R.string.account_failure, accountFailureLabel(IllegalStateException("private storage")))
        assertEquals(R.string.account_failure, accountFailureLabel(AccountDataFailure()))
    }
}
