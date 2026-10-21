package com.helix.app.account

/** Presentation only. Access remains governed by the durable encrypted session and request revision. */
enum class AccountStatus { SIGNED_OUT, SIGNED_IN, REAUTH_REQUIRED, UNAVAILABLE }
