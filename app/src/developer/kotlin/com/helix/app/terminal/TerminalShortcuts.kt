package com.helix.app.terminal

import org.connectbot.terminal.VTermKey

/** Native key codes preserve cursor/application mode; text uses the same bounded input queue as typing. */
internal data class TerminalShortcut(
    val id: String,
    val label: String,
    val key: Int? = null,
    val text: String? = null,
) {
    init {
        require((key == null) != (text == null))
    }

    fun send(
        isWriter: Boolean,
        sendKey: (Int) -> Unit,
        sendText: (String) -> Unit,
    ): Boolean {
        if (!isWriter) return false
        if (key != null) sendKey(key) else sendText(requireNotNull(text))
        return true
    }
}

internal object TerminalShortcuts {
    val common =
        listOf(
            TerminalShortcut("27", "Esc", key = VTermKey.ESCAPE),
            TerminalShortcut("9", "Tab", key = VTermKey.TAB),
            TerminalShortcut("3", "Ctrl-C", text = "\u0003"),
            TerminalShortcut("4", "Ctrl-D", text = "\u0004"),
            TerminalShortcut("up", "↑", key = VTermKey.UP),
            TerminalShortcut("down", "↓", key = VTermKey.DOWN),
            TerminalShortcut("left", "←", key = VTermKey.LEFT),
            TerminalShortcut("right", "→", key = VTermKey.RIGHT),
        )
    val editing =
        listOf(
            TerminalShortcut("home", "Home", key = VTermKey.HOME),
            TerminalShortcut("end", "End", key = VTermKey.END),
            TerminalShortcut("page-up", "PgUp", key = VTermKey.PAGEUP),
            TerminalShortcut("page-down", "PgDn", key = VTermKey.PAGEDOWN),
            TerminalShortcut("delete", "Del", key = VTermKey.DEL),
            TerminalShortcut("1", "Ctrl-A", text = "\u0001"),
            TerminalShortcut("5", "Ctrl-E", text = "\u0005"),
            TerminalShortcut("12", "Ctrl-L", text = "\u000c"),
            TerminalShortcut("18", "Ctrl-R", text = "\u0012"),
            TerminalShortcut("21", "Ctrl-U", text = "\u0015"),
            TerminalShortcut("23", "Ctrl-W", text = "\u0017"),
            TerminalShortcut("26", "Ctrl-Z", text = "\u001a"),
        )
    val symbols =
        listOf("/", "-", "~", "|", "\\", "`", "$", "&", ";", "<", ">", "'", "\"", "(", ")", "[", "]", "{", "}")
            .map { TerminalShortcut("symbol-${it.single().code}", it, text = it) }
}
