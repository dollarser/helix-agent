package com.helix.app.eval

import android.os.SystemClock
import com.helix.app.provider.BuiltInModel
import com.helix.provider.api.wire.WireRequest
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Test-only persisted lifecycle guard; optional bounded mode exercises timeout regressions. Never logs payloads. */
internal class OwnedModelRequestGate(
    private val file: File,
    private val marker: String,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val wall: () -> Long = System::currentTimeMillis,
    createNew: Boolean = false,
    bounded: Boolean = true,
) {
    init {
        file.parentFile!!.mkdirs()
        if (createNew) {
            if (!file.createNewFile()) reject()
        } else if (!file.isFile) {
            reject()
        }
        mutate { state ->
            if (!state.has("started")) {
                if (!createNew) reject()
                state
                    .put("started", elapsed())
                    .put("wall", wall())
                    .put("count", 0)
                    .put("closed", false)
                    .put("bounded", bounded)
            }
        }
    }

    fun admit(request: WireRequest) {
        if (request.method != "POST" || request.url != BuiltInModel.GENERATION_URL) reject()
        val body = request.body?.toString(Charsets.UTF_8) ?: reject()
        val payload =
            try {
                JSONObject(body)
            } catch (_: org.json.JSONException) {
                reject()
            }
        if (payload.optString("model") != BuiltInModel.MODEL_ID ||
            payload.optLong("max_tokens", -1) !in 1..4096 || marker !in body
        ) {
            reject()
        }
        mutate { state ->
            val age = elapsed() - state.getLong("started")
            val wallAge = wall() - state.getLong("wall")
            if (state.getBoolean("closed")) reject()
            if (state.getBoolean("bounded")) {
                if (state.getInt("count") >= 24) reject()
                if (age !in 0 until 240_000 || wallAge !in 0 until 240_000) reject()
            }
            state.put("count", state.getInt("count") + 1)
        }
    }

    fun close() =
        mutate {
            it.put("closed", true)
            Unit
        }

    private fun mutate(change: (JSONObject) -> Unit) {
        RandomAccessFile(file, "rw").use { handle ->
            handle.channel.lock().use {
                val state =
                    if (handle.length() == 0L) JSONObject() else JSONObject(handle.readUTF())
                change(state)
                handle.seek(0)
                handle.writeUTF(state.toString())
                handle.setLength(handle.filePointer)
                handle.fd.sync()
            }
        }
    }

    private fun reject(): Nothing = throw IOException("OWNED_MODEL_CASE_REQUEST_REJECTED")
}
