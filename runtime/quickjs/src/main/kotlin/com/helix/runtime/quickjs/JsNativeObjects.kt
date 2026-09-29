package com.helix.runtime.quickjs

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.lang.reflect.Array as ReflectArray

/** Public Java/Android APIs with explicit signatures; no hidden-API or permission bypass. */
internal class JsNativeObjects(
    context: Any,
) {
    private val handles = mutableMapOf(0 to context)
    private var nextHandle = 1

    @Suppress("SpreadOperator") // Java reflection requires varargs; arguments are bounded by IPC.
    fun invoke(request: JsonObject): JsonElement {
        val types = (request["types"] as? JsonArray).orEmpty().map { type(it.jsonPrimitive.content) }.toTypedArray()
        val args = (request["args"] as? JsonArray).orEmpty()
        require(types.size == args.size) { "Parameter types and arguments must match" }
        val values = args.mapIndexed { index, value -> decode(value, types[index]) }.toTypedArray()
        val value =
            when (request.text("op")) {
                "create" -> {
                    type(request.text("type")).getConstructor(*types).newInstance(*values)
                }

                "staticCall" -> {
                    type(request.text("type")).getMethod(request.text("method"), *types).invoke(null, *values)
                }

                "call" -> {
                    val target = resolve(request.getValue("target"))
                    target.javaClass.getMethod(request.text("method"), *types).invoke(target, *values)
                }

                "field" -> {
                    val target = resolve(request.getValue("target"))
                    target.javaClass.getField(request.text("name")).get(target)
                }

                "staticField" -> {
                    type(request.text("type")).getField(request.text("name")).get(null)
                }

                "release" -> {
                    val id = (request.getValue("target") as JsonObject).text("\$ref").toInt()
                    require(id != 0) { "Context is retained until execution ends" }
                    handles.remove(id)
                    null
                }

                else -> {
                    error("Unknown native operation")
                }
            }
        return encode(value)
    }

    private fun resolve(value: JsonElement): Any =
        handles[(value as JsonObject).text("\$ref").toInt()] ?: error("Unknown native object")

    @Suppress("ReturnCount") // Distinct JSON null, handle, array and scalar representations.
    private fun decode(
        value: JsonElement,
        expected: Class<*>,
    ): Any? {
        if (value == JsonNull) return null
        if (value is JsonObject) return resolve(value)
        if (expected.isArray) {
            val items = value as JsonArray
            val component = requireNotNull(expected.componentType)
            return ReflectArray.newInstance(component, items.size).also { array ->
                items.forEachIndexed { index, item -> ReflectArray.set(array, index, decode(item, component)) }
            }
        }
        val text = value.jsonPrimitive.content
        return when (expected) {
            java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> text.toBooleanStrict()
            java.lang.Byte.TYPE, java.lang.Byte::class.java -> text.toByte()
            java.lang.Short.TYPE, java.lang.Short::class.java -> text.toShort()
            java.lang.Integer.TYPE, java.lang.Integer::class.java -> text.toInt()
            java.lang.Long.TYPE, java.lang.Long::class.java -> text.toLong()
            java.lang.Float.TYPE, java.lang.Float::class.java -> text.toFloat()
            java.lang.Double.TYPE, java.lang.Double::class.java -> text.toDouble()
            java.lang.Character.TYPE, java.lang.Character::class.java -> text.single()
            else -> text
        }
    }

    private fun encode(value: Any?): JsonElement =
        when (value) {
            null -> {
                JsonNull
            }

            is String -> {
                JsonPrimitive(value)
            }

            is Char -> {
                JsonPrimitive(value.toString())
            }

            is Boolean -> {
                JsonPrimitive(value)
            }

            is Number -> {
                if (value is Long) JsonPrimitive(value.toString()) else JsonPrimitive(value)
            }

            else -> {
                require(handles.size < 1024) { "Native object limit reached; release unused handles" }
                val id = nextHandle++
                handles[id] = value
                JsonObject(mapOf("\$ref" to JsonPrimitive(id)))
            }
        }

    private fun type(name: String): Class<*> =
        when (name) {
            "boolean" -> {
                java.lang.Boolean.TYPE
            }

            "byte" -> {
                java.lang.Byte.TYPE
            }

            "short" -> {
                java.lang.Short.TYPE
            }

            "int" -> {
                java.lang.Integer.TYPE
            }

            "long" -> {
                java.lang.Long.TYPE
            }

            "float" -> {
                java.lang.Float.TYPE
            }

            "double" -> {
                java.lang.Double.TYPE
            }

            "char" -> {
                java.lang.Character.TYPE
            }

            else -> {
                if (name.endsWith(
                        "[]",
                    )
                ) {
                    ReflectArray.newInstance(type(name.dropLast(2)), 0).javaClass
                } else {
                    Class.forName(name)
                }
            }
        }
}
