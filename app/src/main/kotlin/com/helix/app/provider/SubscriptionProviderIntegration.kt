package com.helix.app.provider

import android.content.Context
import com.helix.core.storage.HelixStorage
import com.helix.provider.api.ModelProvider
import com.helix.provider.api.ProbeOutcome
import com.helix.provider.api.ProviderConfig

/** Shared variant contract; Consumer supplies no registration, client, probe override or account UI. */
internal interface SubscriptionProviderIntegration {
    fun recoverInterruptedResult(
        context: Context,
        storage: HelixStorage,
        turnId: String,
        modelCallId: String,
        localOnly: Boolean,
    ): SubscriptionRecoveredOutput? = null

    fun inspectInterruptedJob(
        context: Context,
        storage: HelixStorage,
        turnId: String,
        modelCallId: String,
        stop: Boolean,
    ): SubscriptionRecoveryStatus = SubscriptionRecoveryStatus.UNKNOWN

    fun ensureRegistered(storage: HelixStorage)

    fun create(
        context: Context,
        config: ProviderConfig,
    ): ModelProvider?

    fun isManaged(providerId: String): Boolean

    suspend fun probe(
        config: ProviderConfig,
        provider: ModelProvider,
    ): ProbeOutcome?

    suspend fun openAccount(
        context: Context,
        providerId: String,
    ): ManagedProviderAccountResult
}
