package com.helix.runtime.cli.app

import android.content.Context

/** Lazy process-local initialization shared by subscription activities and service only. */
internal object SubscriptionRuntimeEnvironment {
    @Synchronized
    fun initialize(context: Context): SubscriptionDnsSettings {
        SubscriptionDnsOverrides.settings?.let { return it }
        val preferences = context.getSharedPreferences("subscription_dns", Context.MODE_PRIVATE)
        return SubscriptionDnsSettings(
            read = { preferences.getString("entries", null) },
            write = { value ->
                check(preferences.edit().putString("entries", value).commit()) { "DNS configuration save failed" }
            },
        ).also { SubscriptionDnsOverrides.settings = it }
    }
}

internal object SubscriptionDnsOverrides {
    @Volatile
    var settings: SubscriptionDnsSettings? = null
}
