package com.helix.app.provider

import android.content.Context
import com.helix.core.storage.HelixStorage
import com.helix.provider.api.ModelProvider
import com.helix.provider.api.ProbeOutcome
import com.helix.provider.api.ProviderConfig

/** Consumer/store builds contain no subscription provider client or registration. */
internal object SubscriptionProviderModule : SubscriptionProviderIntegration {
    override fun ensureRegistered(storage: HelixStorage) = Unit

    override fun create(
        context: Context,
        config: ProviderConfig,
    ): ModelProvider? = null

    override fun isManaged(providerId: String): Boolean = false

    override suspend fun probe(
        config: ProviderConfig,
        provider: ModelProvider,
    ): ProbeOutcome? = null

    override suspend fun openAccount(
        context: Context,
        providerId: String,
    ): ManagedProviderAccountResult = ManagedProviderAccountResult.NOT_SUPPORTED
}
