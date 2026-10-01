package com.helix.app.terminal

import org.connectbot.terminal.VTermKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalShortcutsTest {
    private val all = TerminalShortcuts.common + TerminalShortcuts.editing + TerminalShortcuts.symbols

    @Test fun idsAreUniqueAndEveryShortcutHasExactlyOneInputKind() {
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertTrue(all.all { (it.key == null) != (it.text == null) })
        assertThrows(IllegalArgumentException::class.java) { TerminalShortcut("x", "x") }
        assertThrows(IllegalArgumentException::class.java) { TerminalShortcut("x", "x", key = 1, text = "x") }
    }

    @Test fun readOnlyConnectionCannotEmitAnyShortcut() {
        all.forEach { shortcut ->
            assertFalse(shortcut.send(false, { error("key emitted") }, { error("text emitted") }))
        }
    }

    @Test fun navigationUsesNativeKeysRatherThanFixedEscapeSequences() {
        val keys = mutableListOf<Int>()
        all.single { it.id == "up" }.send(true, keys::add, { error("raw arrow bytes") })
        all.single { it.id == "home" }.send(true, keys::add, { error("raw home bytes") })
        all.single { it.id == "delete" }.send(true, keys::add, { error("raw delete bytes") })
        assertEquals(listOf(VTermKey.UP, VTermKey.HOME, VTermKey.DEL), keys)
    }

    @Test fun controlAndShellSymbolsRemainExactAndNeverAppendAnEnter() {
        val text = mutableListOf<String>()
        all.single { it.id == "3" }.send(true, { error("wrong kind") }, text::add)
        assertEquals(listOf("\u0003"), text)
        assertTrue(TerminalShortcuts.symbols.all { it.text?.length == 1 && '\n' !in it.text.orEmpty() })
        assertTrue(TerminalShortcuts.symbols.map { it.text }.containsAll(listOf("|", "\\", "~", "$", "`")))
    }
}
