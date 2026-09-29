@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER") // Pinned Zipline JNI string channel; device contract tested.

package com.helix.runtime.quickjs

import android.content.Context
import app.cash.zipline.QuickJs
import app.cash.zipline.internal.bridge.CallChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** String-only JNI ABI. Native objects remain in the per-execution handle table. */
interface JsNativeBridge {
    fun invoke(request: String): String
}

/** Direct app-UID APIs, deliberately not a Dispatcher bridge or a filesystem sandbox. */
internal class JsNativeHost(
    context: Context,
    private val deadlineNanos: Long,
    private val cancelled: AtomicBoolean,
) : JsNativeBridge {
    private val objects = JsNativeObjects(context)

    override fun invoke(request: String): String {
        checkRunning()
        require(request.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Native request too large" }
        val args = Json.parseToJsonElement(request).jsonObject
        val result =
            when (args.text("op")) {
                "readText" -> {
                    File(args.text("path")).inputStream().use { JsonPrimitive(readText(it)) }
                }

                "writeText" -> {
                    File(args.text("path")).writeText(args.text("text"))
                    JsonNull
                }

                "list" -> {
                    val names = File(args.text("path")).list() ?: error("Directory unavailable")
                    JsonArray(names.map(::JsonPrimitive))
                }

                "mkdirs" -> {
                    JsonPrimitive(File(args.text("path")).mkdirs())
                }

                "delete" -> {
                    JsonPrimitive(File(args.text("path")).delete())
                }

                "request" -> {
                    request(args)
                }

                else -> {
                    objects.invoke(args)
                }
            }
        checkRunning()
        val encoded = result.toString()
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Native result too large" }
        return encoded
    }

    private fun checkRunning() {
        check(!cancelled.get() && System.nanoTime() < deadlineNanos) { "Native execution interrupted" }
    }

    private fun readText(input: java.io.InputStream): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= MAX_BYTES) {
            checkRunning()
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray()
        require(bytes.size <= MAX_BYTES) { "Native result too large" }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun request(args: JsonObject): JsonElement {
        val url = URL(args.text("url"))
        require(url.protocol in setOf("http", "https")) { "Expected HTTP or HTTPS URL" }
        val connection = url.openConnection() as HttpURLConnection
        try {
            val remainingMs = ((deadlineNanos - System.nanoTime()) / 1_000_000).coerceIn(1, 10_000).toInt()
            connection.connectTimeout = remainingMs
            connection.readTimeout = remainingMs
            connection.requestMethod = args.text("method")
            connection.instanceFollowRedirects = false
            (args["headers"] as? JsonObject)?.forEach { (name, value) ->
                connection.setRequestProperty(name, value.jsonPrimitive.content)
            }
            val body = args["body"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val bodyText = stream?.use(::readText).orEmpty()
            return JsonObject(mapOf("status" to JsonPrimitive(status), "body" to JsonPrimitive(bodyText)))
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val MAX_BYTES = 256 * 1024

        fun install(
            js: QuickJs,
            context: Context,
            deadlineNanos: Long,
            cancelled: AtomicBoolean,
        ) {
            val host = JsNativeHost(context, deadlineNanos, cancelled)
            js.initOutboundChannel(
                object : CallChannel {
                    override fun call(callJson: String): String = nativeReply { host.invoke(callJson) }

                    override fun disconnect(instanceName: String): Boolean = false
                },
            )
            js.evaluate(BOOTSTRAP, "helix-native.js")
        }

        // Java exceptions must return through the string ABI, not escape the execution thread.
        // This is an error result, never a claim that a native effect was rolled back.
        @Suppress("TooGenericExceptionCaught") // Host API exceptions cross JNI as data; fatal Errors escape.
        internal fun nativeReply(action: () -> String): String =
            try {
                JsonObject(mapOf("value" to Json.parseToJsonElement(action()))).toString()
            } catch (error: Exception) {
                val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
                if (cause is Error) throw cause
                val detail = "${cause.javaClass.simpleName}: ${cause.message.orEmpty().take(512)}"
                JsonObject(mapOf("error" to JsonPrimitive(detail))).toString()
            }

        private val BOOTSTRAP =
            """
            globalThis.native = (() => {
              const bridge = app_cash_zipline_outboundChannel;
              const invoke = request => {
                const reply = JSON.parse(bridge.call(JSON.stringify(request)));
                if (reply.error !== undefined) throw new Error(reply.error);
                return reply.value;
              };
              return Object.freeze({
                files: Object.freeze({
                  readText: path => invoke({op:'readText',path}),
                  writeText: (path,text) => invoke({op:'writeText',path,text}),
                  list: path => invoke({op:'list',path}),
                  mkdirs: path => invoke({op:'mkdirs',path}),
                  delete: path => invoke({op:'delete',path})
                }),
                net: Object.freeze({
                  request: (url,method='GET',body=null,headers={}) => invoke({op:'request',url,method,body,headers})
                }),
                android: Object.freeze({context: Object.freeze({'${'$'}ref':0})}),
                java: Object.freeze({
                  create: (type,types=[],args=[]) => invoke({op:'create',type,types,args}),
                  call: (target,method,types=[],args=[]) => invoke({op:'call',target,method,types,args}),
                  staticCall: (type,method,types=[],args=[]) => invoke({op:'staticCall',type,method,types,args}),
                  field: (target,name) => invoke({op:'field',target,name}),
                  staticField: (type,name) => invoke({op:'staticField',type,name}),
                  release: target => invoke({op:'release',target})
                })
              });
            })();
            """.trimIndent()
    }
}

internal fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content
