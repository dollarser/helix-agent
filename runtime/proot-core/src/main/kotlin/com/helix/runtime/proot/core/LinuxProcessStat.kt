package com.helix.runtime.proot.core

/** Kernel identity; comm may contain spaces and parentheses. Never infer identity from a PID alone. */
data class LinuxProcessStat(
    val pid: Int,
    val parent: Int,
    val group: Int,
    val session: Int,
    val startTicks: Long,
) {
    companion object {
        fun parse(text: String): LinuxProcessStat {
            require(text.length <= 4096)
            val pid = text.substringBefore(" (").toInt()
            val fields = text.substringAfterLast(") ").trim().split(Regex("\\s+"))
            require(fields.size >= 20)
            return LinuxProcessStat(pid, fields[1].toInt(), fields[2].toInt(), fields[3].toInt(), fields[19].toLong())
                .also { require(it.pid > 1 && it.startTicks > 0) }
        }
    }
}
