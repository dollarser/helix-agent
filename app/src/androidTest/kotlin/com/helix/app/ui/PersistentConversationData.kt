package com.helix.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Process
import com.helix.app.HelixApplication
import com.helix.core.model.TurnState
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.repository.MessageAttachmentRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Genuine Room/content-store rows. Synthetic settled markers are fixture data, never execution evidence. */
internal class PersistentConversationData(
    private val app: HelixApplication,
) {
    private val storage = app.appContainer.storage
    val directory = File(app.noBackupFilesDir, "persistent-volume")

    // Preserve the first invalid sample and its failure instead of repairing its rows in place.
    private val marker = File(directory, "settled-manifest.json")
    val manifest: JSONObject get() = JSONObject(marker.readText())
    val session: String get() = manifest.getString("session")

    fun seed() {
        check(!marker.exists()) { "Existing fixture must be verified, not silently repaired" }
        check(directory.mkdirs() || directory.isDirectory)
        val id = "persistent-volume-${UUID.randomUUID()}"
        marker.writeText(
            JSONObject()
                .put("session", id)
                .put("seedPid", Process.myPid())
                .put("complete", false)
                .toString(),
        )
        storage.sessions.create(id, "Persistent synthetic history", null, null, 1)
        repeat(TURNS / BATCH) { batch ->
            storage.withTransaction { repeat(BATCH) { seedTurn(id, batch * BATCH + it) } }
        }
        marker.writeText(manifest.put("complete", true).toString())
        verify()
    }

    private fun seedTurn(
        id: String,
        index: Int,
    ) {
        val turn = "$id-t-$index"
        var row = storage.turns.start(turn, id, index.toLong())
        for (state in listOf(TurnState.BUILDING_CONTEXT, TurnState.WAITING_MODEL, TurnState.RECEIVING_MODEL)) {
            row = storage.turns.updateState(row, state, 0, null, null)
        }
        val user = "$id-u-$index"
        storage.messages.append(
            user,
            id,
            turn,
            "USER",
            "TEXT",
            "PERSISTENT_NEEDLE_$index " + "Synthetic history. ".repeat(30),
        )
        val calls = JSONArray()
        repeat(TOOLS) { number ->
            val call = "$turn-c-$number"
            val args = JSONObject().put("path", "scope:app:input/synthetic-$index-$number.txt")
            calls.put(
                JSONObject()
                    .put("id", call)
                    .put("localId", call)
                    .put("name", "read")
                    .put("arguments", args.toString()),
            )
            storage.toolCalls.append(call, turn, call, "read", "1", args.toString(), "COMPLETED")
            val result =
                storage.toolResults.append(
                    "$call-result",
                    call,
                    "SUCCEEDED",
                    "Synthetic result $index/$number",
                    null,
                )
            storage.toolResults.markVerified(result)
        }
        storage.messages.append("$turn-step", id, turn, "ASSISTANT", "TOOL_CALLS", calls.toString())
        repeat(TOOLS) { number ->
            val result =
                JSONObject()
                    .put("id", "$turn-c-$number")
                    .put("tool", "read")
                    .put("status", "SUCCEEDED")
                    .put("summary", "Synthetic result $index/$number")
            storage.messages.append("$turn-result-$number", id, turn, "TOOL", "TOOL_RESULT", result.toString())
        }
        storage.messages.append(
            "$id-a-$index",
            id,
            turn,
            "ASSISTANT",
            "TEXT",
            "Persistent answer $index. " + "Synthetic answer. ".repeat(30),
        )
        if (index % IMAGE_EVERY == 0) image(id, turn, user, index)
        storage.turns.updateState(row, TurnState.COMPLETED, TOOLS, index.toLong() + 1, null)
    }

    private fun image(
        id: String,
        turn: String,
        message: String,
        index: Int,
    ) {
        val relative = "input/$id/image-$index.png"
        val file = File(app.filesDir, "workspaces/app/$relative")
        check(requireNotNull(file.parentFile).mkdirs() || requireNotNull(file.parentFile).isDirectory)
        val bitmap = Bitmap.createBitmap(1024, 768, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(index % 256, index / IMAGE_EVERY * 37 % 256, index / IMAGE_EVERY * 59 % 256))
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        val sha = FileContentStore.sha256Hex(file)
        val artifact = "$id-image-$index"
        storage.artifacts.registerOrRefresh(
            artifact,
            id,
            "scope:app:$relative",
            "image/png",
            file.length(),
            sha,
            turn,
            file,
            "INPUT",
        )
        storage.messageAttachments.bind(
            message,
            listOf(MessageAttachmentRepository.Binding(artifact, "synthetic-image", sha)),
        )
    }

    fun verify() {
        check(manifest.getBoolean("complete"))
        check(storage.turns.listBySession(session).size == TURNS)
        check(storage.messages.listBySession(session).size == TURNS * (TOOLS + 3))
        check(storage.turns.listBySession(session).sumOf { storage.toolCalls.listByTurn(it.id).size } == TURNS * TOOLS)
        check(storage.artifacts.listBySession(session).size == TURNS / IMAGE_EVERY)
    }

    fun report(
        phase: String,
        metrics: JSONObject,
    ) {
        val output = File(app.getExternalFilesDir(null), "persistent-volume").also { it.mkdirs() }
        metrics
            .put("session", session)
            .put("pid", Process.myPid())
            .put("turns", TURNS)
            .put("textMessages", TURNS * 2)
            .put("messageRows", TURNS * (TOOLS + 3))
            .put("toolRows", TURNS * TOOLS)
            .put("images", TURNS / IMAGE_EVERY)
        File(output, "$phase.json").writeText(metrics.toString(2))
    }

    companion object {
        const val TURNS = 2000
        private const val BATCH = 100
        private const val TOOLS = 5
        private const val IMAGE_EVERY = 20
    }
}
