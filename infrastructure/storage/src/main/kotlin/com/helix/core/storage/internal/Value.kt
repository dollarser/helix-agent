package com.helix.core.storage.internal

/** Strict JSON subset values produced by [MiniJson] (see its KDoc for the accepted grammar). */
internal sealed interface Value {
    data class Str(
        val value: String,
    ) : Value

    data class Num(
        val value: Long,
    ) : Value

    data object True : Value

    data object False : Value

    data object Null : Value

    data class Obj(
        val entries: LinkedHashMap<String, Value>,
    ) : Value

    data class Arr(
        val items: List<Value>,
    ) : Value
}

/** Parses [text] as an object; throws if the top level is not an object. */
internal fun parseStrictObject(text: String): LinkedHashMap<String, Value> =
    (MiniJson.parse(text) as? Value.Obj)?.entries
        ?: throw IllegalArgumentException("top level must be a JSON object")

internal fun parseStrictArray(text: String): List<Value> =
    (MiniJson.parse(text) as? Value.Arr)?.items
        ?: throw IllegalArgumentException("top level must be a JSON array")

internal fun Value.asString(name: String): String =
    (this as? Value.Str)?.value
        ?: throw IllegalArgumentException("field '$name' must be a string")

internal fun Value.asLong(name: String): Long =
    (this as? Value.Num)?.value
        ?: throw IllegalArgumentException("field '$name' must be an integer")

internal fun Value.asBool(name: String): Boolean =
    when (this) {
        Value.True -> true
        Value.False -> false
        else -> throw IllegalArgumentException("field '$name' must be a boolean")
    }
