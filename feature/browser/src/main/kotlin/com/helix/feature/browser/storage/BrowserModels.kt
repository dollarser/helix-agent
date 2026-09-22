package com.helix.feature.browser.storage

import java.util.UUID

/**
 * A user-saved bookmark.
 */
data class Bookmark(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * A browsing history entry.
 */
data class HistoryItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val visitedAt: Long = System.currentTimeMillis(),
)

/**
 * A quick-access tile on the browser home page.
 */
data class SpeedDial(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val iconText: String,
)

/**
 * A user-configured JavaScript script injected into matching web pages (Tampermonkey style).
 */
data class UserScript(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val matchPattern: String = "*",
    val code: String,
    val enabled: Boolean = true,
    val runAt: String = "DOCUMENT_END",
)

/**
 * User-configurable browser preferences.
 */
data class BrowserPreferences(
    val searchEngineId: String = "google",
    val adBlockEnabled: Boolean = true,
    val noImageMode: Boolean = false,
    val nightMode: Boolean = false,
    val desktopMode: Boolean = false,
)
