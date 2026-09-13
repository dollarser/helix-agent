package com.helix.runtime.cli.app

import com.helix.core.model.ModelErrorCode
import java.io.IOException

/** Typed at network I/O boundaries; storage and parsing failures remain distinct. */
internal class SubscriptionTransportFailure(
    val code: ModelErrorCode,
    cause: IOException,
) : RuntimeException(cause)
