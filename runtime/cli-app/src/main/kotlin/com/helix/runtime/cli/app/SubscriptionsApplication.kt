package com.helix.runtime.cli.app

import android.app.Application

/** Loads local network preferences only; never binds a service or starts a network request. */
class SubscriptionsApplication : Application() {
    internal lateinit var dnsSettings: SubscriptionDnsSettings
        private set

    override fun onCreate() {
        super.onCreate()
        val preferences = getSharedPreferences("subscription_dns", MODE_PRIVATE)
        dnsSettings =
            SubscriptionDnsSettings(
                read = { preferences.getString("entries", null) },
                write = {
                    check(preferences.edit().putString("entries", it).commit()) {
                        "DNS configuration save failed"
                    }
                },
            )
        SubscriptionDnsOverrides.settings = dnsSettings
    }
}

internal object SubscriptionDnsOverrides {
    @Volatile var settings: SubscriptionDnsSettings? = null
}
