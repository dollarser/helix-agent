package com.helix.app.account

import java.io.File

/** Avatar is installation-local; the account owns its server-synchronized display name. */
internal data class LocalUserProfile(
    val avatarFile: File? = null,
)
