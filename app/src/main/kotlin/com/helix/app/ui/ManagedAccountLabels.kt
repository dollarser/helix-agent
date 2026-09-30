package com.helix.app.ui

import com.helix.app.R
import com.helix.app.provider.ManagedAccountSnapshot

internal fun managedAccountLabel(account: ManagedAccountSnapshot): Int =
    when (account.state) {
        ManagedAccountSnapshot.State.LOGGED_IN -> {
            R.string.provider_account_present
        }

        ManagedAccountSnapshot.State.LOGGED_OUT -> {
            R.string.provider_account_logged_out
        }

        ManagedAccountSnapshot.State.CREDENTIAL_ERROR -> {
            R.string.provider_account_invalid
        }

        ManagedAccountSnapshot.State.UNKNOWN, ManagedAccountSnapshot.State.UNAVAILABLE -> {
            R.string.provider_account_unknown
        }
    }
