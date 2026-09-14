package com.helix.app.provider

import com.helix.provider.api.ModelCatalogResult

/** Only an authenticated remote account catalog qualifies; local fallback model lists do not. */
internal interface SubscriptionConnectionProvider {
    suspend fun connectionCatalog(): ModelCatalogResult?
}
