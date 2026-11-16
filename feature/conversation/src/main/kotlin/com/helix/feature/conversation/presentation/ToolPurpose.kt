package com.helix.feature.conversation.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.helix.core.workspace.FileScopePath
import com.helix.feature.conversation.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Describes the actual operation and target, never guesses a model's unstated intent. */
object ToolPurpose {
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
        name in setOf("read", "write", "edit", "view_image", "documents_inspect", "documents_view") ||
            name in fileActions

    fun actionRes(name: String): Int =
        mobileActions[name] ?: when {
            name == "write" -> R.string.tool_purpose_write
            name == "edit" -> R.string.tool_purpose_edit
            name == "documents_view" -> R.string.tool_purpose_read
            isRead(name) -> R.string.tool_purpose_read
            isInspect(name) -> R.string.tool_purpose_inspect
            name in searchActions -> R.string.tool_purpose_search
            name in interactionActions -> R.string.tool_purpose_interact
            name == "bash" || name == "code_javascript_run" -> R.string.tool_purpose_run
            else -> R.string.tool_purpose_execute
        }

    private val mobileActions =
        mapOf(
            "ui_apps" to R.string.tool_purpose_apps,
            "ui_launch" to R.string.tool_purpose_launch,
            "ui_snapshot" to R.string.tool_purpose_snapshot,
            "ui_screenshot" to R.string.tool_purpose_screenshot,
            "ui_find" to R.string.tool_purpose_find_control,
            "ui_click" to R.string.tool_purpose_click,
            "ui_click_match" to R.string.tool_purpose_click,
            "ui_set_text" to R.string.tool_purpose_input,
            "ui_scroll" to R.string.tool_purpose_scroll,
            "ui_back" to R.string.tool_purpose_back,
            "ui_wait" to R.string.tool_purpose_wait,
            "ui_device" to R.string.tool_purpose_device,
            "ui_gesture" to R.string.tool_purpose_gesture,
            "device_open_url" to R.string.tool_purpose_open_link,
            "device_clipboard_read" to R.string.tool_purpose_clipboard_read,
            "device_clipboard_write" to R.string.tool_purpose_clipboard_write,
            "device_share_text" to R.string.tool_purpose_share_text,
            "device_share_files" to R.string.tool_purpose_share_files,
            "device_create_note" to R.string.tool_purpose_create_note,
            "device_open_file" to R.string.tool_purpose_open_file,
            "device_open_page" to R.string.tool_purpose_open_page,
            "device_open_settings" to R.string.tool_purpose_open_settings,
            "device_prepare_calendar_event" to R.string.tool_purpose_prepare_calendar_event,
            "device_create_calendar_event" to R.string.tool_purpose_create_calendar_event,
            "device_create_reminder" to R.string.tool_purpose_create_reminder,
            "device_cancel_reminder" to R.string.tool_purpose_cancel_reminder,
            "device_notifications_query" to R.string.tool_purpose_inspect,
            "device_list_reminders" to R.string.tool_purpose_inspect,
        )

    // Explicit product presentation identities; remote names never imply an operation.
    private val fileActions =
        setOf(
            "files_list",
            "files_stat",
            "files_mkdir",
            "files_copy",
            "files_move",
            "files_delete",
            "files_archive",
            "files_extract",
        )
    private val readActions =
        setOf(
            "read",
            "memory_read",
            "session_read",
            "skills_read",
            "skills_read_resource",
            "knowledge_read",
            "root_file_read",
            "root_log_read",
            "documents_read",
        )
    private val inspectActions =
        setOf(
            "memory_list",
            "session_status",
            "session_list",
            "skills_list",
            "files_list",
            "files_stat",
            "code_linux_job_status",
            "settings_inspect",
            "root_status",
            "root_package_info",
            "root_process_list",
            "documents_inspect",
        )
    private val searchActions =
        setOf(
            "memory_search",
            "session_search",
            "knowledge_search",
            "tools_search",
            "browser_search",
            "browser_find",
            "ui_find",
            "glob",
            "grep",
            "web_search",
        )
    private val interactionActions =
        setOf(
            "browser_tabs",
            "browser_wait",
            "browser_open",
            "browser_search",
            "browser_navigate",
            "browser_back",
            "browser_forward",
            "browser_reload",
            "browser_snapshot",
            "browser_find",
            "browser_click",
            "browser_type",
            "browser_upload",
            "browser_scroll",
            "browser_screenshot",
            "browser_download",
            "browser_select",
            "browser_press",
            "browser_submit",
            "ui_snapshot",
            "ui_find",
            "ui_click",
            "ui_click_match",
            "ui_long_click",
            "ui_set_text",
            "ui_ime_enter",
            "ui_set_progress",
            "ui_scroll",
            "ui_back",
            "ui_home",
            "ui_wait",
            "ui_device",
            "ui_apps",
            "ui_launch",
            "ui_system",
            "ui_gesture",
            "ui_screenshot",
        )

    private fun isRead(name: String): Boolean = name in readActions

    private fun isInspect(name: String): Boolean = name in inspectActions

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
