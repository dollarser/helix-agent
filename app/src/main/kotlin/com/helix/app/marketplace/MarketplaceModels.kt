package com.helix.app.marketplace

import androidx.annotation.StringRes

enum class MarketplaceItemType {
    CONNECTOR,
    MCP,
    SKILL,
}

enum class MarketplaceAuthRequirement {
    NONE,
    BEARER_TOKEN,
    API_KEY,
    LOCAL,
}

enum class MarketplaceItemStatus {
    NOT_INSTALLED,
    INSTALLED_INACTIVE,
    ACTIVE,
}

data class MarketplaceItem(
    val id: String,
    @param:StringRes val nameRes: Int,
    val type: MarketplaceItemType,
    @param:StringRes val summaryRes: Int,
    @param:StringRes val descriptionRes: Int,
    val author: String,
    val authRequirement: MarketplaceAuthRequirement,
    @param:StringRes val authHintRes: Int? = null,
    val tags: List<String>,
    val payload: String,
    val targetSkillName: String? = null,
    val targetConnectorName: String? = null,
)
