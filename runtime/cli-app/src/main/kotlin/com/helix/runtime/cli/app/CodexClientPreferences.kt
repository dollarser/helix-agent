package com.helix.runtime.cli.app

import android.content.Context

/** Shared only by the non-exported subscription Activity and Runtime service in the same process. */
internal fun codexClientSettings(context: Context): CodexClientVersionSettings {
    val preferences = context.getSharedPreferences("codex_client_settings", Context.MODE_PRIVATE)
    return CodexClientVersionSettings(
        read = { preferences.getString("version", null) },
        write = { value ->
            val editor = preferences.edit()
            if (value == null) editor.remove("version") else editor.putString("version", value)
            check(editor.commit()) { "CODEX_CLIENT_VERSION_SAVE_FAILED" }
        },
    )
}
