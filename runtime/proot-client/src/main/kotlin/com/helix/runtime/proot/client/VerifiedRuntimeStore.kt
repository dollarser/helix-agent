package com.helix.runtime.proot.client

import android.content.Context
import com.helix.runtime.proot.ipc.RuntimeTargetDescriptor
import com.helix.runtime.proot.ipc.RuntimeTargetDescriptorCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream

/**
 * Persistence of the FIRST verified execution-target descriptor (ADR-0007
 * decision 2: "首次验证得到的有界 execution target descriptor 可以持久化"). The
 * persisted anchor is what later re-handshakes compare the live descriptor
 * against (a changed lock fingerprint means the runtime was updated and the
 * user must re-verify, HXA-087 territory).
 *
 * The store is deliberately tiny and self-contained: one bounded JSON document in
 * the app-private filesDir, atomic tmp+rename writes, and corrupt content is
 * treated as "no anchor" (a fresh verification is required) — never as an error
 * the user must debug. Process liveness is NOT stored: an old file does not make
 * a dead process "available".
 */
class VerifiedRuntimeStore(
    file: File,
) {
    constructor(context: Context) : this(File(context.filesDir, FILE_NAME))

    companion object {
        const val FILE_NAME = "proot-runtime/verified-runtime.json"
    }

    data class Entry(
        val descriptor: RuntimeTargetDescriptor,
        val verifiedAtEpochMs: Long,
    )

    private val file = file

    private val json = Json { isLenient = false }

    fun load(): Entry? {
        val text =
            runCatching {
                if (file.exists()) file.readText() else null
            }.getOrNull() ?: return null
        return runCatching { parseEntry(text) }.getOrNull()
    }

    fun save(entry: Entry) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp-${System.nanoTime().toString(16)}")
        FileOutputStream(tmp).use { out ->
            out.write(encodeEntry(entry).encodeToByteArray())
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            runCatching { tmp.delete() }
            error("verified-runtime rename failed")
        }
    }

    fun clear() {
        runCatching { file.delete() }
    }

    private fun encodeEntry(entry: Entry): String {
        val descriptor = RuntimeTargetDescriptorCodec.encode(entry.descriptor)
        val doc =
            buildJsonObject {
                put("descriptor", json.parseToJsonElement(descriptor))
                put("verifiedAtEpochMs", entry.verifiedAtEpochMs)
            }
        return json.encodeToString(JsonObject.serializer(), doc)
    }

    private fun parseEntry(text: String): Entry {
        val obj = json.parseToJsonElement(text) as? JsonObject ?: schemaFail("not an object")
        val keys = obj.keys
        if (keys != setOf("descriptor", "verifiedAtEpochMs")) schemaFail("unexpected keys")
        val descriptorElement = obj["descriptor"] as? JsonObject ?: schemaFail("bad descriptor")
        val descriptorText = json.encodeToString(JsonObject.serializer(), descriptorElement)
        val descriptor = RuntimeTargetDescriptorCodec.parse(descriptorText)
        val whenMs =
            (obj["verifiedAtEpochMs"] as? JsonPrimitive)?.longOrNull
                ?: schemaFail("bad verifiedAtEpochMs")
        return Entry(descriptor, whenMs)
    }

    /** Corrupt-anchor failure point: a typed schema error, never a crash. */
    private fun schemaFail(message: String): Nothing = throw IllegalArgumentException(message)
}
