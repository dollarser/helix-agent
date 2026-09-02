package com.helix.core.workspace

/**
 * Structural rules for [FileScopePath]: what a segment may contain, which separator variants are
 * legal at the string level, and how dot segments are resolved.
 *
 * The rules implement doc 10 (NUL 检查、分隔符归一化、绝对路径拒绝、`./..` 解析) at the string
 * layer; symlink and real-path containment (the filesystem layer) lives in [PathResolution].
 */
object PathSyntax {
    const val MAX_SEGMENTS = 64
    const val MAX_SEGMENT_LENGTH = 255

    /**
     * Normalizes a relative, dot-free-in-output path and returns its canonical segments.
     *
     * Accepted input (fail-closed otherwise, [IllegalArgumentException]):
     * - empty input = the root itself;
     * - no NUL (code 0x00) or any C0/C1/DEL control character;
     * - at most 4096 characters;
     * - forward slash only — backslash and Unicode separators (U+2215/U+2044) are ordinary
     *   characters, never separators; a leading backslash is a backslash absolute path, rejected;
     * - not absolute: no leading `/` (a forward-slash absolute path) and no drive form like
     *   `C:\` (a backslash absolute path);
     * - `.` and `..` segments are resolved against a segment stack; a `..` with an empty stack
     *   is an escape above the root and rejected;
     * - at most [MAX_SEGMENTS] segments of at most [MAX_SEGMENT_LENGTH] characters each.
     *
     * The result contains no empty, `.` or `..` segments and is joined with `/` — the only
     * representation the rest of Helix accepts.
     */
    fun normalizeRelative(input: String): String {
        require(input.length <= MAX_INPUT_LENGTH) { "path exceeds $MAX_INPUT_LENGTH characters" }
        requireNoControlCharacters(input, "path")
        requireNotAbsolute(input)
        return resolveSegments(input)
    }

    /** Structural segment rules: bounded, non-blank, no NUL/control, no dot identity. */
    fun requireValidSegment(
        segment: String,
        label: String = "path segment",
    ) {
        require(segment.length in 1..MAX_SEGMENT_LENGTH) {
            "$label must be 1..$MAX_SEGMENT_LENGTH characters (got ${segment.length})"
        }
        require(segment != "." && segment != "..") { "$label must not be a dot segment" }
        requireNoControlCharacters(segment, label)
        require(!segment.all { it.isWhitespace() }) { "$label must not be blank" }
    }

    private fun resolveSegments(input: String): String {
        val stack = ArrayDeque<String>()
        for (segment in input.split('/')) {
            when (segment) {
                "" -> {}

                // duplicate separators collapse (a//b == a/b)
                "." -> {}

                // dot segments never appear in the canonical form
                ".." -> {
                    require(!stack.isEmpty()) { "path escapes the scope root (.. above root)" }
                    stack.removeLast()
                }

                else -> {
                    require(stack.size < MAX_SEGMENTS) { "path has more than $MAX_SEGMENTS segments" }
                    requireValidSegment(segment)
                    stack.addLast(segment)
                }
            }
        }
        return stack.joinToString("/")
    }

    private fun requireNotAbsolute(input: String) {
        require(!input.startsWith("\\")) { "path must not be absolute (backslash absolute path)" }
        require(!input.startsWith("/")) { "path must not be absolute (forward-slash absolute path)" }
        require(!input.isDriveFormAbsolute()) { "path must not be absolute (drive-form absolute path)" }
    }

    /** Windows drive form, e.g. `C:\` or `d:/` — never a legal relative segment. */
    private fun String.isDriveFormAbsolute(): Boolean {
        val driveCharOk = length >= 3 && this[0].isLetter()
        val colon = driveCharOk && this[1] == ':'
        return colon && (this[2] == '/' || this[2] == '\\')
    }

    private fun requireNoControlCharacters(
        text: String,
        label: String,
    ) {
        for (i in text.indices) {
            val code = text[i].code
            require(code != 0x00) { "$label contains NUL" }
            require(code !in 0x01..0x1F && code != 0x7F && code !in 0x80..0x9F) {
                "$label contains a control character"
            }
        }
    }

    private const val MAX_INPUT_LENGTH = 4096
}
