package com.helix.app.provider

import com.helix.provider.api.ModelProvider
import com.helix.provider.api.ProbeOutcome
import com.helix.provider.api.ProviderConfig

/** Variant-supplied operations for providers whose accounts belong to a separate runtime. */
class ManagedProviderHooks(
    val isManaged: (String) -> Boolean = { false },
    val probe: suspend (ProviderConfig, ModelProvider) -> ProbeOutcome? = { _, _ -> null },
    val openAccount: suspend (String) -> ManagedProviderAccountResult = {
        ManagedProviderAccountResult.NOT_SUPPORTED
    },
)
