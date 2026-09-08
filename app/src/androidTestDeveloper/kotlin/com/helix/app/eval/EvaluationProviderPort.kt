package com.helix.app.eval

import androidx.test.platform.app.InstrumentationRegistry

/** Host-selected port on the dedicated emulator host, never a model-selected endpoint. */
internal fun evaluationProviderPort(): Int {
    val port = InstrumentationRegistry.getArguments().getString("helix.eval.providerPort")?.toInt() ?: 30008
    require(port in 1024..65535)
    return port
}
