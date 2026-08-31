package com.helix.core.storage.internal

/**
 * Strict canonical JSON subset parser used by the storage-layer codecs (ContentRef, criteria
 * list).
 *
 * Per ADR-0001 the canonical encoder in `core:model` is internal to that module; modules that
 * need storage encodings decide for themselves. This parser follows the same strictness rules
 * as the accepted ADR-0001 subset so storage rows keep the same audit/debug characteristics:
 *
 * - objects: `{` `name` `:` value (`,` `name` `:` value)* `}`; duplicate keys rejected;
 * - arrays: `[` value (`,` value)* `]`;
 * - strings: RFC 8259 with `\" \\ \/ \b \f \n \r \t \uXXXX` escapes; raw control chars rejected;
 * - numbers: signed 64-bit integers only; no leading zeros; no floats or exponents;
 * - literals: `true` / `false` / `null`;
 * - trailing content rejected; insignificant whitespace between tokens limited to
 *   space/tab/CR/LF (parity with the core:model ADR-0001 parser).
 *
 * All failures throw [IllegalArgumentException] (via `require`).
 */
internal object MiniJson {
    private val HEX_DIGITS: Set<Char> = "0123456789abcdefABCDEF".toSet()

    /** Insignificant whitespace, exactly the ADR-0001 subset (see [Reader.skipWhitespace]). */
    private val WHITESPACE: Set<Char> = " \t\r\n".toSet()

    fun parse(text: String): Value {
        val reader = Reader(text)
        val value = parseValue(reader)
        reader.skipWhitespace()
        require(reader.atEnd()) { "trailing content at ${reader.pos}" }
        return value
    }

    private fun parseValue(reader: Reader): Value {
        reader.skipWhitespace()
        val ch = requireNotNull(reader.peek()) { "unexpected end of input" }
        return when (ch) {
            '{' -> parseObject(reader)
            '[' -> parseArray(reader)
            '"' -> Value.Str(parseString(reader))
            '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> parseNumber(reader)
            't' -> parseLiteral(reader, "true", Value.True)
            'f' -> parseLiteral(reader, "false", Value.False)
            'n' -> parseLiteral(reader, "null", Value.Null)
            else -> throw IllegalArgumentException("unexpected char '$ch' at ${reader.pos}")
        }
    }

    private fun parseObject(reader: Reader): Value.Obj {
        reader.expect('{')
        val entries = LinkedHashMap<String, Value>()
        reader.skipWhitespace()
        if (reader.peek() == '}') {
            reader.next()
            return Value.Obj(entries)
        }
        while (true) {
            reader.skipWhitespace()
            val key = parseString(reader)
            require(!entries.containsKey(key)) { "duplicate key '$key'" }
            reader.skipWhitespace()
            reader.expect(':')
            entries[key] = parseValue(reader)
            reader.skipWhitespace()
            when (reader.peek()) {
                ',' -> {
                    reader.next()
                }

                '}' -> {
                    reader.next()
                    return Value.Obj(entries)
                }

                else -> {
                    require(false) { "expected ',' or '}' at ${reader.pos}" }
                }
            }
        }
    }

    private fun parseArray(reader: Reader): Value.Arr {
        reader.expect('[')
        val items = mutableListOf<Value>()
        reader.skipWhitespace()
        if (reader.peek() == ']') {
            reader.next()
            return Value.Arr(items)
        }
        while (true) {
            items += parseValue(reader)
            reader.skipWhitespace()
            when (reader.peek()) {
                ',' -> {
                    reader.next()
                }

                ']' -> {
                    reader.next()
                    return Value.Arr(items)
                }

                else -> {
                    require(false) { "expected ',' or ']' at ${reader.pos}" }
                }
            }
        }
    }

    private fun parseString(reader: Reader): String {
        reader.expect('"')
        val out = StringBuilder()
        while (true) {
            val ch = requireNotNull(reader.peek()) { "unexpected end of input" }
            if (ch == '"') {
                reader.next()
                return out.toString()
            }
            if (ch == '\\') {
                reader.next()
                out.append(parseEscape(reader))
            } else {
                require(ch.code >= ' '.code) { "unescaped control char at ${reader.pos}" }
                out.append(ch)
                reader.next()
            }
        }
    }

    @Suppress("CyclomaticComplexMethod") // one branch per JSON escape; rule false positive for a parser
    private fun parseEscape(reader: Reader): Char {
        val esc = requireNotNull(reader.peek()) { "unexpected end of input" }
        val result =
            when (esc) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> parseUnicodeEscape(reader)
                else -> throw IllegalArgumentException("bad escape '\\$esc' at ${reader.pos}")
            }
        if (esc != 'u') reader.next()
        return result
    }

    private fun parseUnicodeEscape(reader: Reader): Char {
        val hex =
            (1..4).joinToString("") { offset ->
                val c = requireNotNull(reader.peekAt(reader.pos + offset)) { "unexpected end of input" }
                require(c in HEX_DIGITS) { "bad \\u escape at ${reader.pos}" }
                c.toString()
            }
        repeat(5) { reader.next() }
        val code = requireNotNull(hex.toIntOrNull(16)) { "bad \\u escape at ${reader.pos}" }
        return code.toChar()
    }

    private fun parseNumber(reader: Reader): Value.Num {
        val start = reader.pos
        if (reader.peek() == '-') reader.next()
        val first = requireNotNull(reader.peek()) { "bad number at $start" }
        require(first.isDigit()) { "bad number at $start" }
        reader.next()
        if (first == '0') {
            val after = reader.peek()
            require(after == null || !after.isDigit()) { "leading zero at $start" }
        } else {
            while (reader.peek() != null && reader.peek()!!.isDigit()) reader.next()
        }
        val token = reader.text.substring(start, reader.pos)
        val value = requireNotNull(token.toLongOrNull()) { "number out of 64-bit range at $start" }
        return Value.Num(value)
    }

    private fun parseLiteral(
        reader: Reader,
        literal: String,
        value: Value,
    ): Value {
        require(reader.text.startsWith(literal, reader.pos)) { "expected literal '$literal' at ${reader.pos}" }
        repeat(literal.length) { reader.next() }
        return value
    }

    private class Reader(
        internal val text: String,
    ) {
        internal var pos = 0
            private set

        fun atEnd(): Boolean = pos >= text.length

        fun peek(): Char? = if (pos < text.length) text[pos] else null

        fun peekAt(offset: Int): Char? = if (offset < text.length) text[offset] else null

        fun next() {
            if (pos < text.length) pos++
        }

        fun expect(ch: Char) {
            require(peek() == ch) { "expected '$ch' at $pos" }
            next()
        }

        fun skipWhitespace() {
            // Parity with the ADR-0001 parser in core:model: only space/tab/CR/LF are
            // insignificant. Char.isWhitespace() would also accept e.g. U+001C..U+001F,
            // U+1680 and U+2000..U+3000, letting one codec accept what the other rejects.
            while (pos < text.length && WHITESPACE.contains(text[pos])) pos++
        }
    }
}
