package com.helix.core.policy

/**
 * ADR-PERMISSIONS-001 §3 limited rm command rule (HXA-209, phase A slice).
 *
 * Detects an EXPLICIT `rm -rf <non-empty target>` submitted to the Agent Shell
 * execution entry (`code.linux.run`, exactly one of the `argv` / `script`
 * input forms). A match requires at least a precise one-time approval in every
 * session mode; it is never a permanent denial and never a read-only bypass.
 *
 * The match set is the contract's, not an implementation choice — expanding it
 * needs an explicit contract change (ADR §3):
 *
 *  - command name: the resolved name of the command word is `rm`
 *    (a path-prefixed or quoted form such as `/bin/rm` counts);
 *  - flag: exactly the combined short form token `-rf`. Option variants
 *    (`-fr`, `-r -f`, `-rfv`, `--recursive --force`) are NOT matched and fall
 *    to the mode's regular unknown-effect rules;
 *  - operands: at least one non-empty target. Shell redirections are not
 *    operands; a token after `--` is always a target;
 *  - clearly parseable positions only: the argv exec, and per-segment command
 *    words of the `/bin/sh` script once comments, quoting, heredoc bodies,
 *    shell keywords and leading `NAME=value` assignments are resolved. Text
 *    inside strings, comments and heredoc bodies never matches; parenthesized
 *    subshells and command substitutions ARE checked because their commands
 *    do execute. A variant the rule cannot see is a contract-limited reminder
 *    gap, not a bypass of CUSTOM file-mutation DENY (that limit is enforced
 *    at the effect level, not by this rule).
 */
object RmCommandRule {
    /** Terminal verdict of the rule for one submitted Shell entry call. */
    sealed interface Verdict {
        /**
         * A clearly parseable explicit `rm -rf` with [targets] was found.
         * The targets are the unquoted operand texts (no directory check —
         * an uncertain target still requires the approval, per ADR §3).
         */
        data class RmRfDir(
            val targets: List<String>,
        ) : Verdict

        /** No explicit `rm -rf` match; the mode's regular effect rules apply. */
        data object NotApplicable : Verdict
    }

    /** `argv` form: one direct exec, no shell interpretation of the tokens. */
    fun checkArgv(argv: List<String>): Verdict {
        val command = argv.getOrNull(0)
        return if (command != null && baseName(command) == "rm") {
            parseRmOperands(argv.drop(1))
        } else {
            Verdict.NotApplicable
        }
    }

    /** `script` form: a `/bin/sh` script; only clearly parseable command positions are checked. */
    fun checkScript(script: String): Verdict {
        for (tokens in ShellSegments.of(script)) {
            val command = commandWord(tokens) ?: continue
            if (baseName(command) == "rm") {
                val verdict = parseRmOperands(tokens.dropWhile { it != command }.drop(1))
                if (verdict is Verdict.RmRfDir) return verdict
            }
        }
        return Verdict.NotApplicable
    }

    /**
     * The command word of one script segment: the first token that is neither
     * a shell keyword (`then`, `fi`, `do`, …) nor a leading `NAME=value`
     * assignment. Null when the segment has no command (e.g. a bare `fi`).
     */
    private fun commandWord(tokens: List<String>): String? {
        for (token in tokens) {
            if (token in KEYWORDS || ASSIGNMENT.find(token) != null) continue
            return token
        }
        return null
    }

    /** Operands of a command word already resolved as `rm` (flags/targets in either order). */
    private fun parseRmOperands(tokens: List<String>): Verdict {
        var rfSeen = false
        var optionsClosed = false
        val targets = mutableListOf<String>()
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            when {
                !optionsClosed && token == "--" -> optionsClosed = true

                !optionsClosed && token == "-rf" -> rfSeen = true

                !optionsClosed && looksLikeFlag(token) -> Unit

                // other flag: -x, --long
                isRedirect(token) -> if (token.isBareRedirectOperator()) index++

                // its file operand
                else -> targets.add(token)
            }
            index++
        }
        return if (rfSeen && targets.isNotEmpty()) Verdict.RmRfDir(targets) else Verdict.NotApplicable
    }

    /** A short/long option token: `-x` or `--long`. A lone `-` is an operand (stdin). */
    private fun looksLikeFlag(token: String): Boolean = token.length > 1 && token[0] == '-' && !isRedirect(token)

    private fun String.isBareRedirectOperator(): Boolean = this == ">" || this == ">>" || this == "<"

    /**
     * `2>/dev/null`, `> /x` (bare operator), `1>&2`, `>>log` — a redirection,
     * not an operand. The file part may be attached (`2>/dev/null`) or a
     * separate token (`> /x`, consumed by the caller).
     */
    private fun isRedirect(token: String): Boolean =
        token.isBareRedirectOperator() || REDIRECT_PREFIX.find(token) != null

    private fun baseName(command: String): String = command.substringAfterLast('/')

    private val ASSIGNMENT = Regex("^[A-Za-z_][A-Za-z0-9_]*=")
    private val REDIRECT_PREFIX = Regex("^[0-9]*(>>?|<|&>)")
    private val KEYWORDS =
        setOf(
            "if",
            "then",
            "else",
            "elif",
            "fi",
            "for",
            "in",
            "while",
            "until",
            "do",
            "done",
            "case",
            "esac",
            "select",
            "function",
            "time",
            "coproc",
        )
}

/** Mutable scan state of the minimal `/bin/sh` tokenizer (see [RmCommandRule]). */
private class ScanState(
    val text: String,
) {
    val segments = mutableListOf<List<String>>()
    val current = mutableListOf<String>()
    val out = StringBuilder()
    var i = 0
    var wordStart = true

    /** `<<` (or `<<-`) at the current position opens a heredoc. */
    fun isHeredocStart(): Boolean = text[i] == '<' && i + 1 < text.length && text[i + 1] == '<'

    /** Appends the accumulated word to the segment, if any. */
    fun flushToken() {
        if (out.isNotEmpty()) {
            current.add(out.toString())
            out.clear()
        }
    }
}

/** Characters that cannot be part of a heredoc delimiter word. */
private const val DELIMITER_STOP = " \t\n;&|()<>"

/** The characters a backslash may quote inside double quotes. */
private const val DOUBLE_QUOTE_ESCAPES = "\"\\"

/**
 * Minimal `/bin/sh` tokenizer for the clearly-parseable-command-position
 * question. Emits one unquoted token list per command segment; segments
 * end at `;` `&` `|` (and the doubled forms), newlines, parentheses and
 * braces. Single/double quotes group text (an operator inside a quote is
 * data); a backslash quotes the next character or continues the line;
 * `#` starts a comment at word start; a `<<`/`<<-` heredoc consumes its
 * body lines as data and terminates the command. No expansion, no
 * substitution semantics — anything the tokenizer does not clearly place
 * is simply not a match.
 */
private object ShellSegments {
    fun of(script: String): List<List<String>> {
        val normalized = script.replace("\r\n", "\n").replace('\r', '\n')
        return scan(ScanState(normalized))
    }
}

private fun scan(s: ScanState): List<List<String>> {
    while (s.i < s.text.length) {
        val c = s.text[s.i]
        when {
            c == '\'' -> {
                s.i = quoted(s, '\'')
            }

            c == '"' -> {
                s.i = quoted(s, '"')
            }

            c in " \t\n;(){}" -> {
                s.i = boundary(s, c)
            }

            c == '#' && s.wordStart -> {
                s.i = comment(s)
            }

            c == '\\' -> {
                s.i = escaped(s)
            }

            c == '&' || c == '|' -> {
                s.i = pipeline(s, c)
            }

            s.isHeredocStart() -> {
                s.i = skipHeredoc(s, s.i)
                // The command line holding the heredoc marker ends with it; the
                // next line starts a fresh command word.
                endSegment(s)
                s.wordStart = true
            }

            else -> {
                s.out.append(c)
                s.wordStart = false
                s.i++
            }
        }
    }
    endSegment(s)
    return s.segments
}

/** Quoted body: raw for single quotes; backslash escapes only `"` and `\` in double quotes. */
private fun quoted(
    s: ScanState,
    quote: Char,
): Int {
    if (quote == '\'') {
        val end = s.text.indexOf(quote, s.i + 1)
        val bodyEnd = if (end < 0) s.text.length else end
        s.out.append(s.text.substring(s.i + 1, bodyEnd))
        s.wordStart = false
        return if (end < 0) s.text.length else end + 1
    }
    var j = s.i + 1
    val body = StringBuilder()
    while (j < s.text.length) {
        val c = s.text[j]
        if (c == '\\' && j + 1 < s.text.length && s.text[j + 1] in DOUBLE_QUOTE_ESCAPES) {
            body.append(s.text[j + 1])
            j += 2
        } else if (c == '"') {
            break
        } else {
            body.append(c)
            j++
        }
    }
    s.out.append(body)
    s.wordStart = false
    return if (j < s.text.length) j + 1 else s.text.length
}

/** Space/tab closes the current word; newline and the `;`/parens/braces separators close the segment. */
private fun boundary(
    s: ScanState,
    c: Char,
): Int {
    if (c in " \t") s.flushToken() else endSegment(s)
    s.wordStart = true
    return s.i + 1
}

/** A comment runs to the end of the line (absent newline: to the end of input). */
private fun comment(s: ScanState): Int {
    val nl = s.text.indexOf('\n', s.i)
    s.wordStart = true
    return if (nl < 0) s.text.length else nl + 1
}

/** A backslash quotes the next character, or continues the line before a newline. */
private fun escaped(s: ScanState): Int {
    val next = s.text.getOrNull(s.i + 1)
    if (next != null && next != '\n') {
        s.out.append(next)
        s.wordStart = false
    }
    return s.i + if (next == null) 1 else 2
}

/** `&` and `|` end the segment; the doubled forms `&&`/`||` consume both characters. */
private fun pipeline(
    s: ScanState,
    c: Char,
): Int {
    endSegment(s)
    var j = s.i + 1
    if (j < s.text.length && s.text[j] == c) j++
    s.wordStart = true
    return j
}

/**
 * Skips a `<<`/`<<-` heredoc: the delimiter word (possibly quoted), the rest
 * of the current line, and all body lines up to the line equal to the
 * delimiter. Returns the index to resume scanning at.
 */
private fun skipHeredoc(
    s: ScanState,
    at: Int,
): Int {
    val (delimiter, after) = readDelimiter(s, at)
    return if (delimiter.isEmpty()) after else skipBody(s, after, delimiter)
}

/** Reads the heredoc delimiter word at [at] (the `<<`/`<<-` position). */
private fun readDelimiter(
    s: ScanState,
    at: Int,
): Pair<String, Int> {
    var j = at + 2
    if (j < s.text.length && s.text[j] == '-') j++
    if (j < s.text.length && (s.text[j] == '\'' || s.text[j] == '"')) {
        val quote = s.text[j]
        val end = s.text.indexOf(quote, j + 1)
        val body = if (end < 0) s.text.substring(j + 1) else s.text.substring(j + 1, end)
        return body to (if (end < 0) s.text.length else end + 1)
    }
    var k = j
    while (k < s.text.length && s.text[k] !in DELIMITER_STOP) k++
    return s.text.substring(j, k) to k
}

/** Consume body lines until the line equal to [delimiter] (leading tabs trimmed). */
private fun skipBody(
    s: ScanState,
    from: Int,
    delimiter: String,
): Int {
    val lineEnd = s.text.indexOf('\n', from)
    var resume = if (lineEnd < 0) s.text.length else lineEnd + 1
    while (resume < s.text.length) {
        val nextLine = s.text.indexOf('\n', resume)
        val body = if (nextLine < 0) s.text.substring(resume) else s.text.substring(resume, nextLine)
        if (body.trimStart('\t') == delimiter) return if (nextLine < 0) s.text.length else nextLine + 1
        resume = if (nextLine < 0) s.text.length else nextLine + 1
    }
    return resume
}

private fun endSegment(s: ScanState) {
    s.flushToken()
    if (s.current.isNotEmpty()) s.segments.add(s.current.toList())
    s.current.clear()
}
