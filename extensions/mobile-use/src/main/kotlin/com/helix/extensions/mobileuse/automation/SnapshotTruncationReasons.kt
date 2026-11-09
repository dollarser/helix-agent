package com.helix.extensions.mobileuse.automation

/** One bounded vocabulary for capture, IPC and tool output; incomplete reads remain explicit. */
internal object SnapshotTruncationReasons {
    val all = setOf("DEPTH_LIMIT", "NODE_LIMIT", "TEXT_LIMIT", "FIELD_LIMIT", "MISSING_CHILD", "OUTPUT_LIMIT")

    fun checked(values: List<String>): Set<String> {
        require(values.size <= all.size && values.all { it in all })
        require(values.distinct().size == values.size)
        return values.toSet()
    }
}
