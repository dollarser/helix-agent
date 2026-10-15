package com.helix.smoke

import android.app.UiAutomation
import android.os.Build
import android.os.ParcelFileDescriptor

/** Read-only SQLite queries against the disposable R8 installation on an authorized emulator. */
internal class ReleaseDatabaseProbe(
    private val automation: UiAutomation,
    private val target: String,
) {
    init {
        require(target.matches(Regex("com\\.helix\\.validation\\.[a-z][a-z0-9]*\\.developer")))
        check(shell("su 0 id -u").trim() == "0") { "This boundary fixture requires emulator read-only SQLite access" }
    }

    fun call(id: String): String {
        require(id.matches(Regex("r8-[a-z0-9-]+")))
        return query(
            "SELECT c.state||'|'||coalesce(r.status,'')||'|'||t.state||'|'||coalesce(r.summary,'')||'|'||c.id " +
                "FROM tool_calls c JOIN turns t ON t.id=c.turnId " +
                "LEFT JOIN tool_results r ON r.toolCallId=c.id WHERE c.modelIntent='Check $id';",
        )
    }

    fun count(id: String): Int {
        require(id.matches(Regex("r8-[a-z0-9-]+")))
        return query("SELECT count(*) FROM tool_calls WHERE modelIntent='Check $id';").trim().toInt()
    }

    fun reviewCount(id: String): Int {
        require(id.matches(Regex("r8-[a-z0-9-]+")))
        return query(
            "SELECT count(*) FROM tool_call_reviews r JOIN tool_calls c ON c.id=r.toolCallId " +
                "WHERE c.modelIntent='Check $id';",
        ).toInt()
    }

    fun diagnostic(): String =
        query(
            "SELECT c.name||'|'||c.state||'|'||t.state||'|'||coalesce(substr(r.summary,1,160),'') " +
                "FROM tool_calls c JOIN turns t ON t.id=c.turnId " +
                "LEFT JOIN tool_results r ON r.toolCallId=c.id LIMIT 30;",
        )

    private fun query(sql: String): String {
        // UiAutomation tokenizes command arguments without a shell. SQL belongs on stdin.
        check(Build.VERSION.SDK_INT >= 31) { "Read-only R8 execution probes require API 31 or newer" }
        val pipes = automation.executeShellCommandRwe("su 0 sqlite3 -readonly /data/user/0/$target/databases/helix.db")
        ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { it.write((sql + "\n").toByteArray()) }
        val output =
            ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use {
                it.readBytes().toString(Charsets.UTF_8).trim()
            }
        val error =
            ParcelFileDescriptor.AutoCloseInputStream(pipes[2]).use {
                it.readBytes().toString(Charsets.UTF_8).trim()
            }
        check(error.isEmpty()) { "Read-only fixture query failed: $error" }
        return output
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
}
