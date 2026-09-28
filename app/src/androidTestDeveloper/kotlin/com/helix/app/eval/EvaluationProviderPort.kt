package com.helix.app.eval

import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.model.ProviderProtocol

/** Host-selected port on the dedicated emulator host, never a model-selected endpoint. */
internal fun evaluationProviderPort(): Int {
    val port = InstrumentationRegistry.getArguments().getString("helix.eval.providerPort")?.toInt() ?: 30008
    require(port in 1024..65535)
    return port
}

/** Optional suite-only override used when the evaluation target is Harness behavior, not adapter coverage. */
internal fun evaluationProviderProtocol(default: ProviderProtocol): ProviderProtocol {
    val value = InstrumentationRegistry.getArguments().getString("helix.eval.protocolOverride") ?: return default
    return ProviderProtocol.valueOf(value)
}

/** Optional P5-only case selector. HXA-100 omits it and continues to execute the full suite. */
internal fun evaluationCaseId(): String? =
    InstrumentationRegistry.getArguments().getString("helix.eval.caseId")?.also {
        require(it.matches(Regex("[a-z]+-[0-9]{3}"))) { "invalid evaluation case id" }
    }
