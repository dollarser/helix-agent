package com.helix.app.terminal

import com.helix.app.internal.LineStore

/** Four reusable display slots. Execution UUIDs, process identity and retained audit stay separate. */
internal class TerminalWindowSlots(
    private val store: LineStore,
) {
    fun present(states: List<ManualTerminal.State>): List<ManualTerminal.State> =
        synchronized(lock) {
            val available = states.filter { it.canConnect }.distinctBy { it.sessionId }
            require(available.size <= CAPACITY) { "CAPACITY_FULL" }
            val saved = store.lines(KEY)
            val slots = decode(saved).filterKeys { id -> available.any { it.sessionId == id } }.toMutableMap()
            available.forEach { state ->
                if (state.sessionId !in slots) {
                    slots[state.sessionId] = (1L..CAPACITY.toLong()).first { it !in slots.values }
                }
            }
            val replacement =
                listOf("1") + slots.entries.sortedBy { it.value }.flatMap { listOf(it.key, it.value.toString()) }
            if (saved != replacement) {
                store.setLines(KEY, replacement)
                check(store.lines(KEY) == replacement) { "Terminal windows could not be persisted" }
            }
            available.map { it.copy(displayNumber = slots.getValue(it.sessionId)) }
        }

    fun number(sessionId: String): Long? = synchronized(lock) { decode(store.lines(KEY))[sessionId] }

    private fun decode(lines: List<String>): Map<String, Long> {
        if (lines.isEmpty()) return emptyMap()
        require(lines.first() == "1" && lines.size % 2 == 1)
        val entries = lines.drop(1).chunked(2)
        val slots =
            entries.associate { (id, slot) ->
                require(id.isNotBlank() && '\n' !in id)
                id to requireNotNull(slot.toLongOrNull()).also { require(it in 1L..CAPACITY.toLong()) }
            }
        require(slots.size == entries.size && slots.values.toSet().size == slots.size)
        return slots
    }

    private companion object {
        const val KEY = "windows"
        const val CAPACITY = 4
        val lock = Any()
    }
}
