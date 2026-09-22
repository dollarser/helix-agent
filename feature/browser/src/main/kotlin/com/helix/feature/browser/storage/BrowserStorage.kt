@file:Suppress("TooManyFunctions")

package com.helix.feature.browser.storage

import android.content.Context
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.UUID

/**
 * Manages persistent storage for the browser feature: bookmarks, history, speed dials,
 * user scripts, and browser preferences.
 *
 * Backed by simple, atomic JSON files inside the application's private files directory.
 */
class BrowserStorage(
    context: Context,
) {
    private val baseDir = File(context.filesDir, "browser").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    private val bookmarksFile = File(baseDir, "bookmarks.json")
    private val historyFile = File(baseDir, "history.json")
    private val speedDialsFile = File(baseDir, "speed_dials.json")
    private val userScriptsFile = File(baseDir, "userscripts.json")
    private val preferencesFile = File(baseDir, "preferences.json")

    // ---------------------------------------------------------------- Bookmarks

    fun loadBookmarks(): List<Bookmark> {
        val content = readFileSafe(bookmarksFile) ?: return emptyList()
        return try {
            val root = json.parseToJsonElement(content).jsonArray
            root.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString()
                val title = obj["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val url = obj["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val createdAt = obj["createdAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
                Bookmark(id, title, url, createdAt)
            }
        } catch (
            @Suppress("SwallowedException") e: SerializationException,
        ) {
            emptyList()
        } catch (
            @Suppress("SwallowedException") e: IllegalArgumentException,
        ) {
            emptyList()
        }
    }

    fun saveBookmarks(bookmarks: List<Bookmark>) {
        val array =
            buildJsonArray {
                bookmarks.forEach { bm ->
                    add(
                        buildJsonObject {
                            put("id", JsonPrimitive(bm.id))
                            put("title", JsonPrimitive(bm.title))
                            put("url", JsonPrimitive(bm.url))
                            put("createdAt", JsonPrimitive(bm.createdAt))
                        },
                    )
                }
            }
        writeFileSafe(bookmarksFile, array.toString())
    }

    // ---------------------------------------------------------------- History

    fun loadHistory(): List<HistoryItem> {
        val content = readFileSafe(historyFile) ?: return emptyList()
        return try {
            val root = json.parseToJsonElement(content).jsonArray
            root.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString()
                val title = obj["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val url = obj["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val visitedAt = obj["visitedAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
                HistoryItem(id, title, url, visitedAt)
            }
        } catch (
            @Suppress("SwallowedException") e: SerializationException,
        ) {
            emptyList()
        } catch (
            @Suppress("SwallowedException") e: IllegalArgumentException,
        ) {
            emptyList()
        }
    }

    fun saveHistory(history: List<HistoryItem>) {
        val array =
            buildJsonArray {
                history.forEach { item ->
                    add(
                        buildJsonObject {
                            put("id", JsonPrimitive(item.id))
                            put("title", JsonPrimitive(item.title))
                            put("url", JsonPrimitive(item.url))
                            put("visitedAt", JsonPrimitive(item.visitedAt))
                        },
                    )
                }
            }
        writeFileSafe(historyFile, array.toString())
    }

    // ---------------------------------------------------------------- Speed Dials

    fun loadSpeedDials(): List<SpeedDial> {
        val content = readFileSafe(speedDialsFile) ?: return defaultSpeedDials()
        return try {
            val root = json.parseToJsonElement(content).jsonArray
            val list =
                root.mapNotNull { element ->
                    val obj = element as? JsonObject ?: return@mapNotNull null
                    val id = obj["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString()
                    val title = obj["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val url = obj["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val iconText = obj["iconText"]?.jsonPrimitive?.content ?: title.take(2).uppercase()
                    SpeedDial(id, title, url, iconText)
                }
            list.ifEmpty { defaultSpeedDials() }
        } catch (
            @Suppress("SwallowedException") e: SerializationException,
        ) {
            defaultSpeedDials()
        } catch (
            @Suppress("SwallowedException") e: IllegalArgumentException,
        ) {
            defaultSpeedDials()
        }
    }

    fun saveSpeedDials(speedDials: List<SpeedDial>) {
        val array =
            buildJsonArray {
                speedDials.forEach { sd ->
                    add(
                        buildJsonObject {
                            put("id", JsonPrimitive(sd.id))
                            put("title", JsonPrimitive(sd.title))
                            put("url", JsonPrimitive(sd.url))
                            put("iconText", JsonPrimitive(sd.iconText))
                        },
                    )
                }
            }
        writeFileSafe(speedDialsFile, array.toString())
    }

    private fun defaultSpeedDials(): List<SpeedDial> =
        listOf(
            SpeedDial(title = "GitHub", url = "https://github.com", iconText = "GH"),
            SpeedDial(title = "Google", url = "https://www.google.com", iconText = "G"),
            SpeedDial(title = "Wikipedia", url = "https://www.wikipedia.org", iconText = "W"),
            SpeedDial(title = "Bilibili", url = "https://www.bilibili.com", iconText = "B"),
            SpeedDial(title = "V2EX", url = "https://www.v2ex.com", iconText = "V"),
            SpeedDial(title = "HuggingFace", url = "https://huggingface.co", iconText = "HF"),
        )

    // ---------------------------------------------------------------- User Scripts

    fun loadUserScripts(): List<UserScript> {
        val content = readFileSafe(userScriptsFile) ?: return defaultUserScripts()
        return try {
            val root = json.parseToJsonElement(content).jsonArray
            val list =
                root.mapNotNull { element ->
                    val obj = element as? JsonObject ?: return@mapNotNull null
                    val id = obj["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString()
                    val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val matchPattern = obj["matchPattern"]?.jsonPrimitive?.content ?: "*"
                    val code = obj["code"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
                    val runAt = obj["runAt"]?.jsonPrimitive?.content ?: "DOCUMENT_END"
                    UserScript(id, name, matchPattern, code, enabled, runAt)
                }
            list.ifEmpty { defaultUserScripts() }
        } catch (
            @Suppress("SwallowedException") e: SerializationException,
        ) {
            defaultUserScripts()
        } catch (
            @Suppress("SwallowedException") e: IllegalArgumentException,
        ) {
            defaultUserScripts()
        }
    }

    fun saveUserScripts(scripts: List<UserScript>) {
        val array =
            buildJsonArray {
                scripts.forEach { s ->
                    add(
                        buildJsonObject {
                            put("id", JsonPrimitive(s.id))
                            put("name", JsonPrimitive(s.name))
                            put("matchPattern", JsonPrimitive(s.matchPattern))
                            put("code", JsonPrimitive(s.code))
                            put("enabled", JsonPrimitive(s.enabled))
                            put("runAt", JsonPrimitive(s.runAt))
                        },
                    )
                }
            }
        writeFileSafe(userScriptsFile, array.toString())
    }

    private fun defaultUserScripts(): List<UserScript> =
        listOf(
            UserScript(
                name = "Force Dark Mode Helper",
                matchPattern = "*",
                code = "// Injected user script example\nconsole.log('[Helix] Page loaded with user script support.');",
                enabled = false,
                runAt = "DOCUMENT_END",
            ),
        )

    // ---------------------------------------------------------------- Preferences

    fun loadPreferences(): BrowserPreferences {
        val content = readFileSafe(preferencesFile) ?: return BrowserPreferences()
        return try {
            val obj = json.parseToJsonElement(content).jsonObject
            BrowserPreferences(
                searchEngineId = obj["searchEngineId"]?.jsonPrimitive?.content ?: "google",
                adBlockEnabled = obj["adBlockEnabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                noImageMode = obj["noImageMode"]?.jsonPrimitive?.booleanOrNull ?: false,
                nightMode = obj["nightMode"]?.jsonPrimitive?.booleanOrNull ?: false,
                desktopMode = obj["desktopMode"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        } catch (
            @Suppress("SwallowedException") e: SerializationException,
        ) {
            BrowserPreferences()
        } catch (
            @Suppress("SwallowedException") e: IllegalArgumentException,
        ) {
            BrowserPreferences()
        }
    }

    fun savePreferences(prefs: BrowserPreferences) {
        val obj =
            buildJsonObject {
                put("searchEngineId", JsonPrimitive(prefs.searchEngineId))
                put("adBlockEnabled", JsonPrimitive(prefs.adBlockEnabled))
                put("noImageMode", JsonPrimitive(prefs.noImageMode))
                put("nightMode", JsonPrimitive(prefs.nightMode))
                put("desktopMode", JsonPrimitive(prefs.desktopMode))
            }
        writeFileSafe(preferencesFile, obj.toString())
    }

    // ---------------------------------------------------------------- IO helpers

    private val files = BrowserFileStore()

    private fun readFileSafe(file: File): String? = files.read(file)

    private fun writeFileSafe(
        file: File,
        content: String,
    ) = files.write(file, content)
}
