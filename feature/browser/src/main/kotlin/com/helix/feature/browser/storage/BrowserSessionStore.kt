package com.helix.feature.browser.storage

import com.helix.feature.browser.BrowserTabController
import com.helix.feature.browser.BrowserUrlDecision
import com.helix.feature.browser.BrowserUrlPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Address metadata only: no DOM, form state, page tokens, old tab IDs or task authority. */
data class SavedBrowserTab(
    val url: String,
    val title: String?,
    val desktopMode: Boolean,
)

data class BrowserSession(
    val tabs: List<SavedBrowserTab> = emptyList(),
    val selectedIndex: Int = -1,
    val isClosed: Boolean = false,
) {
    companion object {
        fun from(state: BrowserTabController.State): BrowserSession {
            val ordinary = state.tabs.filter { !it.isIncognito && it.originSessionId == null }
            return BrowserSession(
                ordinary.map { SavedBrowserTab(it.url, it.title?.take(2048), it.isDesktopMode) },
                ordinary.indexOfFirst { it.id == state.selectedId }.takeIf { it >= 0 }
                    ?: if (ordinary.isEmpty()) -1 else 0,
                state.isClosed,
            )
        }
    }
}

/** Version 1, bounded atomic private file. Invalid input fails visibly; no legacy readers. */
class BrowserSessionStore(
    private val file: File,
) {
    fun read(): BrowserSession {
        val content = BrowserFileStore().read(file) ?: return BrowserSession()
        val root = Json.parseToJsonElement(content).jsonObject
        require(root.getValue("version").jsonPrimitive.int == 1) { "Unsupported browser session" }
        val rows = root.getValue("tabs").jsonArray
        require(rows.size <= BrowserTabController.DEFAULT_MAX_TABS) { "Too many saved browser tabs" }
        val tabs =
            rows.map { row ->
                val value = row.jsonObject
                val url = value.getValue("url").jsonPrimitive.content
                require(BrowserUrlPolicy.evaluate(url) is BrowserUrlDecision.Allowed) {
                    "Invalid saved browser address"
                }
                SavedBrowserTab(
                    url,
                    value["title"]?.jsonPrimitive?.content?.also { require(it.length <= 2048) },
                    value.getValue("desktopMode").jsonPrimitive.boolean,
                )
            }
        val selected = root.getValue("selectedIndex").jsonPrimitive.int
        require(if (tabs.isEmpty()) selected == -1 else selected in tabs.indices) { "Invalid browser selection" }
        return BrowserSession(tabs, selected, root.getValue("isClosed").jsonPrimitive.boolean)
    }

    fun write(session: BrowserSession) {
        val content =
            buildJsonObject {
                put("version", JsonPrimitive(1))
                put("selectedIndex", JsonPrimitive(session.selectedIndex))
                put("isClosed", JsonPrimitive(session.isClosed))
                put(
                    "tabs",
                    JsonArray(
                        session.tabs.map { tab ->
                            buildJsonObject {
                                put("url", JsonPrimitive(tab.url))
                                tab.title?.let { put("title", JsonPrimitive(it)) }
                                put("desktopMode", JsonPrimitive(tab.desktopMode))
                            }
                        },
                    ),
                )
            }
        BrowserFileStore().write(file, content.toString())
    }
}
