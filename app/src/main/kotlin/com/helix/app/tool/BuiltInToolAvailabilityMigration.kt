package com.helix.app.tool

import com.helix.core.storage.HelixStorage

/** Moves user availability choices once. No aliases, call rewriting or approval migration. */
internal fun migrateBuiltInToolAvailability(storage: HelixStorage) {
    storage.withTransaction {
        storage.toolAvailability.moveToolStates(
            "built-in",
            mapOf(
                "create_goal" to setOf("goal.create"),
                "get_goal" to setOf("goal.get"),
                "update_goal" to setOf("goal.update"),
                "helix.settings" to setOf("settings.inspect"),
                "helix.settings.apply" to setOf("settings.apply"),
                "tool.result.read" to setOf("tools.read_result"),
                "android.open_uri" to setOf("device.open_url"),
                "android.open_settings" to setOf("device.open_settings"),
                "clipboard.read" to setOf("device.clipboard_read"),
                "clipboard.write" to setOf("device.clipboard_write"),
                "android.share" to setOf("device.share_text", "device.create_note", "device.share_files"),
                "notifications.query" to setOf("device.notifications_query"),
                "calendar.prepare_event" to setOf("device.prepare_calendar_event"),
                "calendar.commit_event" to setOf("device.create_calendar_event"),
            ),
        )
    }
}
