package com.helix.tools.files

/** Segment-aware glob: *, ?, and a whole ** segment. Iterative DP has no regex backtracking. */
internal object PathGlob {
    fun matches(
        pattern: String,
        path: String,
        ignoreCase: Boolean,
    ): Boolean {
        require(pattern.length <= 256 && !pattern.startsWith('/') && '\\' !in pattern)
        val parts = pattern.split('/')
        require(parts.none { it == ".." || it.isEmpty() })
        val names = path.split('/')
        var previous = BooleanArray(names.size + 1).also { it[0] = true }
        for (part in parts) {
            val current = BooleanArray(names.size + 1)
            if (part == "**") {
                current[0] = previous[0]
                for (i in names.indices) current[i + 1] = previous[i + 1] || current[i]
            } else {
                for (i in names.indices) current[i + 1] = previous[i] && segment(part, names[i], ignoreCase)
            }
            previous = current
        }
        return previous[names.size]
    }

    private fun segment(
        pattern: String,
        name: String,
        ignoreCase: Boolean,
    ): Boolean {
        var previous = BooleanArray(name.length + 1).also { it[0] = true }
        for (c in pattern) {
            val current = BooleanArray(name.length + 1)
            current[0] = c == '*' && previous[0]
            for (i in name.indices) {
                current[i + 1] =
                    if (c == '*') {
                        previous[i + 1] || current[i]
                    } else {
                        previous[i] && (c == '?' || c.equals(name[i], ignoreCase))
                    }
            }
            previous = current
        }
        return previous[name.length]
    }
}
