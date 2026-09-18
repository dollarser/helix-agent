package com.helix.runtime.proot.app

import java.io.File

/** /proc descendant BFS (host view; PRoot children are real host processes). */
@Suppress("SwallowedException", "LoopWithTooManyJumpStatements", "TooGenericExceptionCaught")
internal fun processTree(rootPid: Int): Set<Int> {
    val result = linkedSetOf<Int>()
    var frontier = listOf(rootPid)
    var depth = 0
    while (frontier.isNotEmpty() && depth < 16) {
        depth++
        val next = mutableListOf<Int>()
        File("/proc")
            .listFiles()
            ?.filter { it.name.all { c -> c in '0'..'9' } }
            ?.forEach { entry ->
                val pid = entry.name.toIntOrNull() ?: return@forEach
                val stat =
                    try {
                        File(entry, "stat").readText()
                    } catch (e: Exception) {
                        // an unreadable /proc entry is not one of ours: skip it
                        return@forEach
                    }
                // ppid is field 4; the comm field (2) may contain spaces, so
                // split from the LAST ')'.
                val closeParen = stat.lastIndexOf(')')
                val fields = stat.substring(closeParen + 2).trim().split(" ")
                if (fields.size > 1 && fields[1].toIntOrNull() in frontier) {
                    result += pid
                    next += pid
                }
            }
        frontier = next
    }
    return result
}
