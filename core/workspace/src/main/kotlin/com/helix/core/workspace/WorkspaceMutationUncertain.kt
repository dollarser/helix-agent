package com.helix.core.workspace

/** The backend was entered and its final effect was not verified. Never retry automatically. */
class WorkspaceMutationUncertain(
    cause: Exception,
) : java.io.IOException("Workspace mutation requires review", cause)
