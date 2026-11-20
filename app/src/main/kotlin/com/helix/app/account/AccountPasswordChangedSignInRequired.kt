package com.helix.app.account

/** The password mutation succeeded; never invite a second mutation when re-authentication fails. */
internal class AccountPasswordChangedSignInRequired : java.io.IOException("Password changed; sign in again")
