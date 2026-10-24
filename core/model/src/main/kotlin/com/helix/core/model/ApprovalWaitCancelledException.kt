package com.helix.core.model

/** The broker guarantees its wait was cancelled before any executor could start. */
class ApprovalWaitCancelledException(
    message: String,
) : IllegalStateException(message)
