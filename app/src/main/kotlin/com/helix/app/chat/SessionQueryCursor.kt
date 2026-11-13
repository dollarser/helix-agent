package com.helix.app.chat

import com.helix.core.storage.repository.SessionAccess
import com.helix.core.storage.repository.SessionHistoryCaller
import com.helix.core.storage.repository.SessionHistoryRepository
import com.helix.tools.framework.ExecutableToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.Base64

/** A continuation never grants authority: all bound fields are checked against fresh durable scope. */
internal data class SessionQueryCursor(
    val binding: String,
    val snapshot: String,
    val through: Long,
    val before: Long,
) {
    fun next(before: Long): String = encode(binding, snapshot, through, before)

    fun validate(
        repository: SessionHistoryRepository,
        caller: SessionHistoryCaller,
        access: SessionAccess,
        checkActive: () -> Unit,
    ) {
        require(snapshot(repository, caller, access, through, checkActive) == snapshot) { "SESSION_DATA_UNAVAILABLE" }
    }

    companion object {
        fun historyScope(
            repository: SessionHistoryRepository,
            caller: SessionHistoryCaller,
            access: SessionAccess,
            call: ExecutableToolCall,
            query: String?,
            checkActive: () -> Unit,
        ): SessionQueryCursor {
            require("beforeSequence" !in call.args)
            val binding =
                JsonArray(
                    listOf(
                        access.sourceSessionId,
                        access.targetSessionId,
                        access.revision.toString(),
                        call.toolName,
                        query.orEmpty(),
                    ).map(::JsonPrimitive),
                ).toString()
            val encoded = call.args["cursor"]?.jsonPrimitive?.content
            if (encoded != null) {
                return decode(encoded).also {
                    require(it.binding == binding && it.before in 0..it.through + 1)
                    it.validate(repository, caller, access, checkActive)
                }
            }
            checkActive()
            val through =
                repository
                    .page(caller, Long.MAX_VALUE, 1, access = access)
                    .messages
                    .firstOrNull()
                    ?.sequence ?: 0L
            require(through in 0 until Long.MAX_VALUE)
            return SessionQueryCursor(
                binding,
                snapshot(repository, caller, access, through, checkActive),
                through,
                through + 1,
            )
        }

        private fun snapshot(
            repository: SessionHistoryRepository,
            caller: SessionHistoryCaller,
            access: SessionAccess,
            through: Long,
            checkActive: () -> Unit,
        ): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            var before = through + 1
            do {
                checkActive()
                val rows = repository.page(caller, before, SessionHistoryRepository.MAX_PAGE, access = access).messages
                rows.forEach { row ->
                    digest.update(
                        JsonArray(
                            listOf(row.id, row.sequence.toString(), row.role, row.contentRef.orEmpty())
                                .map(::JsonPrimitive),
                        ).toString().toByteArray(Charsets.UTF_8),
                    )
                }
                before = rows.lastOrNull()?.sequence ?: 0
            } while (rows.size == SessionHistoryRepository.MAX_PAGE)
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun encode(
            binding: String,
            snapshot: String,
            through: Long,
            before: Long,
        ): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                buildJsonObject {
                    put("binding", binding)
                    put("snapshot", snapshot)
                    put("through", through)
                    put("before", before)
                }.toString().toByteArray(Charsets.UTF_8),
            )

        fun decode(encoded: String): SessionQueryCursor {
            require(encoded.length <= 4096)
            val data: JsonObject =
                Json
                    .parseToJsonElement(
                        String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8),
                    ).jsonObject
            require(data.keys == setOf("binding", "snapshot", "through", "before"))
            return SessionQueryCursor(
                data.getValue("binding").jsonPrimitive.content,
                data.getValue("snapshot").jsonPrimitive.content,
                data
                    .getValue("through")
                    .jsonPrimitive.long
                    .also { require(it in 0 until Long.MAX_VALUE) },
                data.getValue("before").jsonPrimitive.long,
            )
        }
    }
}
