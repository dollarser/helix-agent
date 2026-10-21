package com.helix.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.core.workspace.FileScopePath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Describes the actual operation and target, never guesses a model's unstated intent. */
internal object ToolPurpose {
    @Composable
    fun text(
        name: String,
        arguments: String,
        modelIntent: String? = null,
    ): String {
        val target = target(arguments)
        if (modelIntent != null && !(isFileOperation(name) && target != null)) return modelIntent
        val label = stringResource(actionRes(name))
        return target?.let { "$label · $it" } ?: label
    }

    private fun isFileOperation(name: String): Boolean =
        name in setOf("read", "write", "edit", "view_image", "documents.inspect", "documents.view") ||
            name.startsWith("files.")

    fun actionRes(name: String): Int =
        mobileActions[name] ?: when {
            name == "write" -> R.string.tool_purpose_write
            name == "edit" -> R.string.tool_purpose_edit
            name == "documents.view" -> R.string.tool_purpose_read
            isRead(name) -> R.string.tool_purpose_read
            isInspect(name) -> R.string.tool_purpose_inspect
            name.endsWith(".find") || name.endsWith(".search") -> R.string.tool_purpose_search
            name.startsWith("browser.") || name.startsWith("ui.") -> R.string.tool_purpose_interact
            name == "bash" || name == "code.javascript.run" -> R.string.tool_purpose_run
            else -> R.string.tool_purpose_execute
        }

    private val mobileActions =
        mapOf(
            "ui.apps" to R.string.tool_purpose_apps,
            "ui.launch" to R.string.tool_purpose_launch,
            "ui.snapshot" to R.string.tool_purpose_snapshot,
            "ui.screenshot" to R.string.tool_purpose_screenshot,
            "ui.find" to R.string.tool_purpose_find_control,
            "ui.click" to R.string.tool_purpose_click,
            "ui.click_match" to R.string.tool_purpose_click,
            "ui.set_text" to R.string.tool_purpose_input,
            "ui.scroll" to R.string.tool_purpose_scroll,
            "ui.back" to R.string.tool_purpose_back,
            "ui.wait" to R.string.tool_purpose_wait,
            "ui.device" to R.string.tool_purpose_device,
            "ui.gesture" to R.string.tool_purpose_gesture,
            "device.open_url" to R.string.tool_purpose_open_link,
            "device.clipboard_read" to R.string.tool_purpose_clipboard_read,
            "device.clipboard_write" to R.string.tool_purpose_clipboard_write,
            "device.share_text" to R.string.tool_purpose_share_text,
            "device.share_files" to R.string.tool_purpose_share_files,
            "device.create_note" to R.string.tool_purpose_create_note,
            "device.open_file" to R.string.tool_purpose_open_file,
            "device.open_page" to R.string.tool_purpose_open_page,
            "device.open_settings" to R.string.tool_purpose_open_settings,
            "device.prepare_calendar_event" to R.string.tool_purpose_prepare_calendar_event,
            "device.create_calendar_event" to R.string.tool_purpose_create_calendar_event,
            "device.create_reminder" to R.string.tool_purpose_create_reminder,
            "device.cancel_reminder" to R.string.tool_purpose_cancel_reminder,
            "device.notifications_query" to R.string.tool_purpose_inspect,
            "device.list_reminders" to R.string.tool_purpose_inspect,
        )

    private fun isRead(name: String): Boolean =
        name == "read" || name.endsWith(".read") || name.endsWith(".read_resource")

    private fun isInspect(name: String): Boolean =
        name.endsWith(".inspect") || name.endsWith(".list") || name.endsWith(".stat") || name.endsWith(".info") ||
            name.endsWith(".status") || name.endsWith(".query")

    fun target(arguments: String): String? {
        val fields =
            try {
                Json.parseToJsonElement(arguments) as? JsonObject
            } catch (_: IllegalArgumentException) {
                null
            } ?: return null
        return listOf(
            "destination",
            "path",
            "source",
            "packageName",
            "url",
            "query",
            "tabId",
            "name",
        ).firstNotNullOfOrNull { key ->
            (fields[key] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.takeIf { it.isNotBlank() }
                ?.let { displayTarget(it, key in setOf("destination", "path", "source")) }
        }
    }

    /** File titles show only the name; directories and exact references remain in details. */
    private fun displayTarget(
        raw: String,
        filePath: Boolean,
    ): String {
        val label =
            if (filePath) {
                runCatching { FileScopePath.fromModelReference(raw).name }.getOrNull()
                    ?: raw.trimEnd('/').substringAfterLast('/').ifEmpty { raw }
            } else {
                raw
            }
        val text = label.replace('\n', ' ').replace('\r', ' ')
        return if (text.length <= TARGET_LIMIT) text else text.take(40) + "…" + text.takeLast(TARGET_LIMIT - 41)
    }

    private const val TARGET_LIMIT = 120
}
