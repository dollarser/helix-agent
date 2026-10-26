package com.helix.app.terminal

import com.helix.runtime.proot.core.PtyOutputPage
import com.helix.runtime.proot.ipc.PtySessionReply

/** Read the new PTY's shell prompt, without sending input or replaying a user command. */
internal fun awaitShellPrompt(
    query: () -> PtySessionReply,
    read: (String?) -> PtyOutputPage,
    clock: () -> Long = System::nanoTime,
    pause: () -> Unit = { Thread.sleep(25) },
): PtySessionReply {
    val deadline = clock() + 15_000_000_000L
    var cursor: String? = null
    var tail = ""
    while (clock() < deadline) {
        val reply = query()
        val record = checkNotNull(reply.record) { "SHELL_START_UNCONFIRMED" }
        check(record.phase.name !in setOf("UNKNOWN", "STOPPED", "ACKNOWLEDGED")) { "SHELL_START_FAILED" }
        val page = read(cursor)
        tail = ((if (page.gapBefore) "" else tail) + page.bytes.toString(Charsets.UTF_8)).takeLast(256)
        if (record.phase.name == "RUNNING" && tail.contains("helix> ")) return reply
        check(!page.eof) { "SHELL_START_FAILED" }
        cursor = page.cursor
        pause()
    }
    error("SHELL_START_TIMEOUT")
}
