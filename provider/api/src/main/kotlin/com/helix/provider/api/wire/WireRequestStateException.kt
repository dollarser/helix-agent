package com.helix.provider.api.wire

import java.io.IOException

/** A local request identity/state failure is not a transient network outage. No account data is carried. */
open class WireRequestStateException(
    val reason: Reason,
) : IOException(reason.name) {
    enum class Reason { SIGN_IN_REQUIRED, ACCOUNT_CHANGED, ACCOUNT_UNAVAILABLE }
}
