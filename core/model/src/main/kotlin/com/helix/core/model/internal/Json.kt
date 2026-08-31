package com.helix.core.model.internal

/**
 * Minimal, strict canonical JSON used for the deterministic storage encoding of core:model
 * domain values (see ADR-0001).
 *
 * This is not a general-purpose JSON library:
 * - the writer emits the fixed field order chosen by each domain type, RFC 8259 escapes and
 *   64-bit integers only (no floats, no NaN/Infinity, no leading zeros);
 * - the parser accepts exactly that subset, rejects duplicate keys, trailing characters,
 *   unescaped control characters and anything outside the signed 64-bit integer range.
 *
 * The canonical encoding of tool *arguments* (for approval hashing) is a separate, stricter
 * implementation owned by the Tool framework (HXA-031) and must not be assumed to live here.
 */
internal object Json {
    const val NULL_VALUE = "null"

    fun string(value: String): String = "\"" + escape(value) + "\""

    fun long(value: Long): String = value.toString()

    fun bool(value: Boolean): String = value.toString()

    fun objectBody(pairs: List<Pair<String, String>>): String =
        pairs.joinToString(",", "{", "}") { (key, renderedValue) -> string(key) + ":" + renderedValue }

    fun array(items: List<String>): String = items.joinToString(",", "[", "]")

    /**
     * Renders a map with keys sorted for canonical output; keys must already be valid
     * identifier-style strings (domain types validate them before encoding).
     */
    fun objectFromSortedEntries(entries: List<Pair<String, String>>): String = objectBody(entries.sortedBy { it.first })

    fun escape(value: String): String {
        val sb = StringBuilder(value.length + 2)
        for (c in value) {
            when (c) {
                '"' -> {
                    sb.append("\\\"")
                }

                '\\' -> {
                    sb.append("\\\\")
                }

                '\b' -> {
                    sb.append("\\b")
                }

                '\t' -> {
                    sb.append("\\t")
                }

                '\n' -> {
                    sb.append("\\n")
                }

                '\r' -> {
                    sb.append("\\r")
                }

                0x0C.toChar() -> {
                    sb.append("\\f")
                }

                else -> {
                    if (c.code < 0x20) {
                        sb.append('\\').append('u').append(c.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        return sb.toString()
    }
}

/** Parsed JSON node of the strict subset accepted by [parseJson]. */
internal sealed interface JsonNode {
    data class Str(
        val value: String,
    ) : JsonNode

    data class Num(
        val value: Long,
    ) : JsonNode

    data class Bool(
        val value: Boolean,
    ) : JsonNode

    data class Obj(
        val entries: List<Pair<String, JsonNode>>,
    ) : JsonNode

    data class Arr(
        val items: List<JsonNode>,
    ) : JsonNode

    data object Null : JsonNode
}

/**
 * Parses a strict canonical JSON document. Throws [IllegalArgumentException] with a position
 * hint on any deviation: wrong tokens, floats, leading zeros, out-of-range integers, duplicate
 * object keys, unescaped control characters or trailing content.
 */
internal fun parseJson(text: String): JsonNode = JsonParser(text).parseDocument()

// A recursive-descent parser keeps one function per grammar production; the function count
// is intrinsic to the design, not a god-class smell, so the threshold is suppressed here only.
@Suppress("TooManyFunctions")
private class JsonParser(
    private val s: String,
) {
    private var pos = 0

    val eof: Boolean
        get() = pos >= s.length

    fun parseDocument(): JsonNode {
        skipWhitespace()
        val node = parseValue()
        skipWhitespace()
        if (!eof) throw fail("trailing characters")
        return node
    }

    private fun skipWhitespace() {
        while (!eof && s[pos] in " \t\r\n") pos++
    }

    private fun parseValue(): JsonNode {
        skipWhitespace()
        if (eof) throw fail("unexpected end of input")
        return when (val c = s[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonNode.Str(parseString())
            't' -> parseLiteral("true").let { JsonNode.Bool(true) }
            'f' -> parseLiteral("false").let { JsonNode.Bool(false) }
            'n' -> parseLiteral("null").let { JsonNode.Null }
            in '0'..'9', '-' -> parseNumber()
            else -> throw fail("unexpected character '$c'")
        }
    }

    private fun parseObject(): JsonNode.Obj {
        pos++ // consume '{'
        val entries = mutableListOf<Pair<String, JsonNode>>()
        val keys = mutableSetOf<String>()
        skipWhitespace()
        if (peek() == '}') {
            pos++
            return JsonNode.Obj(entries)
        }
        while (true) {
            skipWhitespace()
            expect('"', "object key")
            val key = readStringBody()
            if (!keys.add(key)) throw fail("duplicate object key")
            expect(':', "object colon")
            entries.add(key to parseValue())
            skipWhitespace()
            if (peek() == ',') {
                pos++
            } else if (peek() == '}') {
                pos++
                return JsonNode.Obj(entries)
            } else {
                throw fail("expected ',' or '}' in object")
            }
        }
    }

    private fun parseArray(): JsonNode.Arr {
        pos++ // consume '['
        val items = mutableListOf<JsonNode>()
        skipWhitespace()
        if (peek() == ']') {
            pos++
            return JsonNode.Arr(items)
        }
        while (true) {
            items.add(parseValue())
            skipWhitespace()
            if (peek() == ',') {
                pos++
            } else if (peek() == ']') {
                pos++
                return JsonNode.Arr(items)
            } else {
                throw fail("expected ',' or ']' in array")
            }
        }
    }

    private fun parseString(): String {
        expect('"', "string")
        return readStringBody()
    }

    /** Reads a string body; the opening quote must already be consumed. */
    private fun readStringBody(): String {
        val sb = StringBuilder()
        while (true) {
            if (eof) throw fail("unterminated string")
            when (val c = s[pos]) {
                '"' -> {
                    pos++
                    return sb.toString()
                }

                '\\' -> {
                    appendEscape(sb)
                }

                else -> {
                    if (c.code < 0x20) throw fail("unescaped control character in string")
                    sb.append(c)
                    pos++
                }
            }
        }
    }

    private fun appendEscape(sb: StringBuilder) {
        pos++ // consume the backslash
        if (eof) throw fail("unterminated escape")
        when (s[pos]) {
            '"' -> sb.append('"')
            '\\' -> sb.append('\\')
            '/' -> sb.append('/')
            'b' -> sb.append('\b')
            'f' -> sb.append(0x0C.toChar())
            'n' -> sb.append('\n')
            'r' -> sb.append('\r')
            't' -> sb.append('\t')
            'u' -> appendUnicodeEscape(sb)
            else -> throw fail("invalid escape character")
        }
        pos++
    }

    private fun appendUnicodeEscape(sb: StringBuilder) {
        if (s.length - pos < 5) throw fail("truncated unicode escape")
        val hex = s.substring(pos + 1, pos + 5)
        val code = hex.toIntOrNull(16) ?: throw fail("invalid unicode escape")
        sb.append(code.toChar())
        pos += 4
    }

    private fun parseNumber(): JsonNode.Num {
        val start = pos
        readDigits()
        rejectFloatExponent()
        return parseLongAt(start)
    }

    private fun readDigits() {
        if (peek() == '-') pos++
        if (eof || s[pos] !in '0'..'9') throw fail("invalid number")
        if (s[pos] == '0') {
            pos++
            if (!eof && s[pos] in '0'..'9') throw fail("leading zeros are not accepted")
        } else {
            while (!eof && s[pos] in '0'..'9') pos++
        }
    }

    private fun rejectFloatExponent() {
        if (eof) return
        val c = s[pos]
        if (c == '.' || c == 'e' || c == 'E') throw fail("floats or exponents are not accepted")
    }

    private fun parseLongAt(start: Int): JsonNode.Num =
        try {
            JsonNode.Num(s.substring(start, pos).toLong())
        } catch (e: NumberFormatException) {
            throw fail("number out of 64-bit range")
        }

    private fun parseLiteral(literal: String) {
        if (!s.startsWith(literal, pos)) throw fail("invalid literal")
        pos += literal.length
    }

    private fun expect(
        expected: Char,
        context: String,
    ) {
        if (peek() != expected) throw fail("expected '$expected' ($context)")
        pos++
    }

    private fun peek(): Char {
        if (eof) throw fail("unexpected end of input")
        return s[pos]
    }

    private fun fail(message: String): IllegalArgumentException {
        val detail = "canonical JSON: $message at position $pos"
        return IllegalArgumentException(detail)
    }
}

// Region: strict decode helpers shared by domain type `parse` implementations.

internal fun JsonNode.requireObject(
    kind: String,
    expectedFields: List<String>,
): Map<String, JsonNode> {
    val obj = this
    require(obj is JsonNode.Obj) { "$kind storage value must be a JSON object" }
    require(obj.entries.size == expectedFields.size) {
        "$kind storage object must contain exactly ${expectedFields.size} fields"
    }
    require(obj.entries.all { (key, _) -> key in expectedFields }) {
        "$kind storage object contains an unexpected field"
    }
    return obj.entries.associate { it.first to it.second }
}

internal fun Map<String, JsonNode>.requireString(field: String): String {
    val node = this[field]
    require(node != null) { "$field is missing" }
    require(node is JsonNode.Str) { "$field must be a JSON string" }
    return node.value
}

internal fun Map<String, JsonNode>.requireLong(field: String): Long {
    val node = this[field]
    require(node != null) { "$field is missing" }
    require(node is JsonNode.Num) { "$field must be a JSON number" }
    return node.value
}

internal fun Map<String, JsonNode>.requireInt(field: String): Int {
    val value = requireLong(field)
    require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "$field is out of int range" }
    return value.toInt()
}

internal fun Map<String, JsonNode>.requireBool(field: String): Boolean {
    val node = this[field]
    require(node != null) { "$field is missing" }
    require(node is JsonNode.Bool) { "$field must be a JSON boolean" }
    return node.value
}

internal fun Map<String, JsonNode>.requireOptionalString(field: String): String? {
    val node = this[field] ?: return null
    return when (node) {
        is JsonNode.Null -> null
        is JsonNode.Str -> node.value
        else -> throw IllegalArgumentException("$field must be a JSON string or null")
    }
}

internal fun Map<String, JsonNode>.requireObjectField(
    field: String,
    kind: String,
    expectedFields: List<String>,
): Map<String, JsonNode> {
    val node = this[field]
    require(node != null) { "$field is missing" }
    return node.requireObject(kind, expectedFields)
}

internal fun Map<String, JsonNode>.requireStringObject(field: String): Map<String, String> {
    val node = this[field]
    require(node is JsonNode.Obj) { "$field must be a JSON object of strings" }
    return node.entries.associate { (key, value) ->
        require(value is JsonNode.Str) { "$field.$key must be a JSON string" }
        key to value.value
    }
}

internal fun Map<String, JsonNode>.requireStringArray(field: String): List<String> {
    val node = this[field]
    require(node is JsonNode.Arr) { "$field must be a JSON array" }
    return node.items.map { item ->
        require(item is JsonNode.Str) { "$field must contain only JSON strings" }
        item.value
    }
}

// End region.
