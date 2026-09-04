package com.helix.app.a2a

import com.helix.core.storage.HelixStorage
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.entity.A2aTaskEntity
import com.helix.core.storage.repository.A2aPersistedTaskState
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.core.workspace.WorkspaceLayout
import com.helix.extensions.a2a.A2aBinding
import com.helix.extensions.a2a.A2aEnabledSkill
import com.helix.extensions.a2a.A2aInterfaceSnapshot
import com.helix.extensions.a2a.A2aNeedsReviewException
import com.helix.extensions.a2a.A2aOutboundArtifact
import com.helix.extensions.a2a.A2aRemoteArtifact
import com.helix.extensions.a2a.A2aRemotePart
import com.helix.extensions.a2a.A2aRemoteTaskState
import com.helix.extensions.a2a.A2aTaskClient
import com.helix.extensions.a2a.A2aTaskStreamListener
import com.helix.extensions.a2a.A2aTaskSubmission
import com.helix.extensions.a2a.A2aTaskUpdate
import com.helix.extensions.a2a.A2aTransportFailure
import com.helix.tools.framework.ExecutableToolCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Suppress("TooManyFunctions") // protocol recovery keeps all transitions behind one durable-task boundary
class A2aTaskRunner(
    private val storage: HelixStorage,
    private val workspace: WorkspaceArtifactStore,
    private val workspaceScopeId: String,
    private val resolveWorkspaceFile: (FileScopePath) -> java.io.File,
    private val client: A2aTaskClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** User/recovery action: query only the already-persisted remote Task; never sends a new Message. */
    fun reconcile(
        toolCallId: String,
        sessionId: String,
    ): JsonObject {
        val persisted = storage.a2aTasks.resolve(toolCallId) ?: error("A2A Task correlation not found")
        val taskId = persisted.taskId ?: throw A2aNeedsReviewException("A2A delivery has no remote Task id")
        val update = client.getTask(persisted.interfaceSnapshot(), bearer(persisted.agentId), taskId)
        persistUpdate(persisted, update)
        return output(update, sessionId, toolCallId)
    }

    /** User cancellation: best effort against the exact saved Task id; never synthesizes a replacement Task. */
    fun cancel(
        toolCallId: String,
        sessionId: String,
    ): JsonObject {
        val persisted = storage.a2aTasks.resolve(toolCallId) ?: error("A2A Task correlation not found")
        val taskId = persisted.taskId ?: throw A2aNeedsReviewException("A2A delivery has no remote Task id")
        val update = client.cancelTask(persisted.interfaceSnapshot(), bearer(persisted.agentId), taskId)
        persistUpdate(persisted, update)
        return output(update, sessionId, toolCallId)
    }

    @Suppress("NestedBlockDepth", "ThrowsCount") // send/poll/cancel is one fail-closed Task state machine
    fun execute(
        call: ExecutableToolCall,
        skill: A2aEnabledSkill,
    ): JsonObject {
        val sessionId = requireNotNull(call.sessionId) { "A2A call has no trusted session binding" }
        val inputHash = canonical(call.args).sha256()
        val existing = storage.a2aTasks.resolve(call.toolCallId)
        if (existing != null) return resumeOnly(existing, skill, sessionId, call)
        var task =
            storage.a2aTasks.begin(
                toolCallId = call.toolCallId,
                agentId = skill.agentId.value,
                skillId = skill.skillId,
                cardHash = skill.cardHash,
                skillHash = skill.skillHash,
                inputHash = inputHash,
                interfaceUrl = skill.interfaceSnapshot.endpoint.full,
                binding = skill.interfaceSnapshot.binding.wireName,
                protocolVersion = skill.interfaceSnapshot.protocolVersion,
                tenant = skill.interfaceSnapshot.tenant,
                updatedAt = now(),
            )
        val submission = submission(call, sessionId, skill)
        val bearer = bearer(skill.agentId.value)
        return try {
            if (call.args["stream"].asBooleanOrFalse()) {
                streamUntilSettled(task, skill, submission, bearer, sessionId, call)
            } else {
                val update = client.send(skill.interfaceSnapshot, bearer, submission)
                task = persistUpdate(task, update)
                settleByPolling(task, update, skill.interfaceSnapshot, bearer, sessionId, call)
            }
        } catch (failure: A2aTransportFailure) {
            if (task.taskId != null) {
                throw A2aNeedsReviewException(
                    "A2A Task reconciliation failed; the saved Task must not be resent",
                    failure,
                )
            }
            if (failure.requestMayHaveArrived) {
                storage.a2aTasks.markDeliveryUnknown(task, now())
                throw A2aNeedsReviewException("A2A SendMessage delivery is ambiguous", failure)
            }
            storage.a2aTasks.markNotSentFailed(task, now())
            throw failure
        }
    }

    private fun resumeOnly(
        persisted: A2aTaskEntity,
        skill: A2aEnabledSkill,
        sessionId: String,
        call: ExecutableToolCall,
    ): JsonObject {
        require(persisted.agentId == skill.agentId.value && persisted.skillId == skill.skillId) {
            "saved A2A Task binding does not match the current Tool"
        }
        require(persisted.cardHash == skill.cardHash && persisted.skillHash == skill.skillHash) {
            "saved A2A Task snapshot no longer matches the enabled Agent Card"
        }
        require(persisted.inputHash == canonical(call.args).sha256()) { "saved A2A Task input hash changed" }
        val taskId = persisted.taskId ?: throw A2aNeedsReviewException("A2A SendMessage has no reconciliable Task id")
        val savedInterface = persisted.interfaceSnapshot()
        val savedBearer = bearer(persisted.agentId)
        val update = client.getTask(savedInterface, savedBearer, taskId)
        val updated = persistUpdate(persisted, update)
        return settleByPolling(updated, update, savedInterface, savedBearer, sessionId, call)
    }

    @Suppress(
        "ReturnCount",
        "ThrowsCount",
        "TooGenericExceptionCaught",
    ) // every exit distinguishes terminal, cancel, disconnect, and reconciliation evidence
    private fun streamUntilSettled(
        initial: A2aTaskEntity,
        skill: A2aEnabledSkill,
        submission: A2aTaskSubmission,
        bearer: String?,
        sessionId: String,
        call: ExecutableToolCall,
    ): JsonObject {
        val latest = AtomicReference<A2aTaskUpdate?>()
        val streamFailureRef = AtomicReference<A2aTransportFailure?>()
        val closed = CountDownLatch(1)
        val persistedRef = AtomicReference(initial)
        val handle =
            client.sendStreaming(
                skill.interfaceSnapshot,
                bearer,
                submission,
                object : A2aTaskStreamListener {
                    override fun onUpdate(update: A2aTaskUpdate) {
                        val normalized = update.copy(sequence = persistedRef.get().lastEventSequence + 1)
                        latest.set(normalized)
                        persistedRef.set(persistUpdate(persistedRef.get(), normalized))
                        if (normalized.final) closed.countDown()
                    }

                    override fun onClosed() {
                        closed.countDown()
                    }

                    override fun onFailure(failure: A2aTransportFailure) {
                        streamFailureRef.set(failure)
                        closed.countDown()
                    }
                },
            )
        try {
            while (!closed.await(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                if (call.cancel.isCancelled()) {
                    handle.cancel()
                    val persisted = persistedRef.get()
                    val taskId =
                        persisted.taskId ?: throw A2aNeedsReviewException("A2A stream cancelled before Task id")
                    val cancelled = client.cancelTask(skill.interfaceSnapshot, bearer, taskId)
                    persistUpdate(persisted, cancelled)
                    return output(cancelled, sessionId, call.toolCallId)
                }
                if (System.currentTimeMillis() >= call.deadline.toEpochMilli()) {
                    handle.cancel()
                    throw A2aNeedsReviewException("A2A stream deadline reached; reconcile saved Task")
                }
            }
        } finally {
            handle.cancel()
        }
        val persisted = persistedRef.get()
        streamFailureRef.get()?.let { streamFailure ->
            val taskId = persisted.taskId
            if (taskId != null) {
                return try {
                    subscribeThenPoll(persisted, skill.interfaceSnapshot, bearer, sessionId, call)
                } catch (reconcileFailure: Exception) {
                    throw A2aNeedsReviewException(
                        "A2A stream disconnected and saved Task reconciliation failed",
                        reconcileFailure,
                    )
                }
            }
            throw streamFailure
        }
        val update = latest.get() ?: throw A2aNeedsReviewException("A2A stream closed without a Task update")
        if (!update.final) {
            requireNotNull(update.taskId) { "A2A stream closed before Task id" }
            return subscribeThenPoll(persisted, skill.interfaceSnapshot, bearer, sessionId, call)
        }
        return output(update, sessionId, call.toolCallId)
    }

    @Suppress("ReturnCount") // cancel, terminal event, and reconciled polling are distinct outcomes
    private fun subscribeThenPoll(
        initial: A2aTaskEntity,
        interfaceSnapshot: A2aInterfaceSnapshot,
        bearer: String?,
        sessionId: String,
        call: ExecutableToolCall,
    ): JsonObject {
        val taskId = requireNotNull(initial.taskId) { "A2A subscription has no Task id" }
        val persistedRef = AtomicReference(initial)
        val latest = AtomicReference<A2aTaskUpdate?>()
        val closed = CountDownLatch(1)
        val handle =
            client.subscribeToTask(
                interfaceSnapshot,
                bearer,
                taskId,
                initial.lastEventId,
                object : A2aTaskStreamListener {
                    override fun onUpdate(update: A2aTaskUpdate) {
                        val normalized = update.copy(sequence = persistedRef.get().lastEventSequence + 1)
                        latest.set(normalized)
                        persistedRef.set(persistUpdate(persistedRef.get(), normalized))
                        if (normalized.final) closed.countDown()
                    }

                    override fun onClosed() = closed.countDown()

                    override fun onFailure(failure: A2aTransportFailure) = closed.countDown()
                },
            )
        try {
            while (!closed.await(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                if (call.cancel.isCancelled()) {
                    val cancelled = client.cancelTask(interfaceSnapshot, bearer, taskId)
                    persistUpdate(persistedRef.get(), cancelled)
                    return output(cancelled, sessionId, call.toolCallId)
                }
                if (System.currentTimeMillis() >= call.deadline.toEpochMilli()) {
                    throw A2aNeedsReviewException("A2A subscription deadline reached; reconcile saved Task")
                }
            }
        } finally {
            handle.cancel()
        }
        latest.get()?.takeIf { it.final || it.state in INTERRUPTED_STATES }?.let {
            return output(it, sessionId, call.toolCallId)
        }
        val reconciled = client.getTask(interfaceSnapshot, bearer, taskId)
        val persisted = persistUpdate(persistedRef.get(), reconciled)
        return settleByPolling(persisted, reconciled, interfaceSnapshot, bearer, sessionId, call)
    }

    private fun settleByPolling(
        initial: A2aTaskEntity,
        initialUpdate: A2aTaskUpdate,
        interfaceSnapshot: A2aInterfaceSnapshot,
        bearer: String?,
        sessionId: String,
        call: ExecutableToolCall,
    ): JsonObject {
        var persisted = initial
        var update = initialUpdate
        while (!update.final && update.state !in INTERRUPTED_STATES) {
            if (call.cancel.isCancelled()) {
                update = client.cancelTask(interfaceSnapshot, bearer, requireNotNull(persisted.taskId))
                persisted = persistUpdate(persisted, update)
                break
            }
            if (System.currentTimeMillis() >= call.deadline.toEpochMilli()) {
                throw A2aNeedsReviewException("A2A Task is still running at the local deadline")
            }
            CountDownLatch(1).await(POLL_MILLIS, TimeUnit.MILLISECONDS)
            update = client.getTask(interfaceSnapshot, bearer, requireNotNull(persisted.taskId))
            persisted = persistUpdate(persisted, update)
        }
        return output(update, sessionId, call.toolCallId)
    }

    private fun submission(
        call: ExecutableToolCall,
        sessionId: String,
        skill: A2aEnabledSkill,
    ): A2aTaskSubmission {
        val refs =
            call.args["artifactRefs"]
                ?.jsonArray
                ?.map { it.asString("artifactRef") }
                .orEmpty()
        require(refs.toSet().size == refs.size) { "A2A artifactRefs contains duplicates" }
        val outputModes =
            call.args["outputModes"]
                ?.jsonArray
                ?.map { it.asString("outputMode") }
                .orEmpty()
        require(outputModes.all { it in skill.outputModes }) { "A2A output mode was not advertised by this Skill" }
        val data = call.args["data"]
        require(data == null || "application/json" in skill.inputModes) {
            "A2A Skill does not advertise application/json input"
        }
        val artifacts = refs.map { outboundArtifact(it, sessionId) }
        require(
            artifacts.all {
                it.mediaType in skill.inputModes
            },
        ) { "A2A Artifact media type was not advertised by this Skill" }
        require("text/plain" in skill.inputModes) { "A2A Skill does not advertise text/plain input" }
        return A2aTaskSubmission(
            messageId = call.toolCallId,
            task = call.args.getValue("task").asString("task"),
            data = data,
            artifacts = artifacts,
            acceptedOutputModes = outputModes,
        )
    }

    private fun outboundArtifact(
        artifactId: String,
        sessionId: String,
    ): A2aOutboundArtifact {
        val artifact = storage.artifacts.resolve(artifactId)
        require(artifact.sessionId == sessionId) { "A2A artifact is not bound to this session" }
        require(artifact.size in 0..MAX_ARTIFACT_BYTES) { "A2A artifact exceeds wire limit" }
        val bytes = workspace.readAll(FileScopePath(workspaceScopeId, artifact.relativePath))
        require(bytes.size.toLong() == artifact.size && FileContentStore.sha256Hex(bytes) == artifact.sha256) {
            "A2A artifact bytes do not match their snapshot"
        }
        return A2aOutboundArtifact(
            filename = artifact.relativePath.substringAfterLast('/').take(256),
            mediaType = artifact.mediaType,
            base64 = Base64.getEncoder().encodeToString(bytes),
            sha256 = artifact.sha256,
        )
    }

    private fun persistUpdate(
        current: A2aTaskEntity,
        update: A2aTaskUpdate,
    ): A2aTaskEntity {
        if (update.state == A2aRemoteTaskState.DIRECT_MESSAGE) {
            return storage.a2aTasks.markDirectCompleted(current, now())
        }
        val taskId = requireNotNull(update.taskId) { "A2A Task update has no task id" }
        return storage.a2aTasks.markAccepted(
            current,
            taskId,
            update.contextId,
            update.state.toPersisted(),
            update.sequence,
            update.eventId,
            now(),
        )
    }

    private fun output(
        update: A2aTaskUpdate,
        sessionId: String,
        toolCallId: String,
    ): JsonObject =
        buildJsonObject {
            put("trust", UNTRUSTED_MARKER)
            put("state", update.state.name)
            update.taskId?.let { put("taskId", it) }
            update.contextId?.let { put("contextId", it) }
            put("parts", buildJsonArray { update.parts.forEach { add(it.toJson()) } })
            put(
                "artifacts",
                buildJsonArray {
                    update.artifacts.forEachIndexed { index, artifact ->
                        add(importArtifact(artifact, index, sessionId, toolCallId))
                    }
                },
            )
        }

    private fun A2aRemotePart.toJson(): JsonObject =
        buildJsonObject {
            put("trust", UNTRUSTED_MARKER)
            when (this@toJson) {
                is A2aRemotePart.Text -> put("text", text)
                is A2aRemotePart.Data -> put("data", data)
                is A2aRemotePart.Raw -> put("rawRejected", "raw content is imported only from Artifact parts")
                is A2aRemotePart.Url -> put("urlRejected", "remote URLs require a separately verified download")
            }
        }

    private fun importArtifact(
        artifact: A2aRemoteArtifact,
        index: Int,
        sessionId: String,
        toolCallId: String,
    ): JsonObject {
        val rawParts = artifact.parts.filterIsInstance<A2aRemotePart.Raw>()
        val urls = artifact.parts.filterIsInstance<A2aRemotePart.Url>()
        val imported =
            rawParts.mapIndexed { partIndex, part ->
                val bytes = decodeBounded(part.base64)
                val filename =
                    "$index-$partIndex-${safeFilename(part.filename ?: "artifact.bin")}"
                val path = FileScopePath(workspaceScopeId, "${WorkspaceLayout.OUTPUT}/a2a/$toolCallId/$filename")
                importRawArtifact(path, bytes, sessionId)
            }
        return buildJsonObject {
            put("remoteArtifactId", artifact.artifactId)
            artifact.name?.let { put("name", it) }
            put(
                "parts",
                buildJsonArray { artifact.parts.filterNot { it is A2aRemotePart.Raw }.forEach { add(it.toJson()) } },
            )
            put("imported", JsonArray(imported))
            if (urls.isNotEmpty()) put("remoteUrlsRejected", urls.size)
            put("trust", UNTRUSTED_MARKER)
        }
    }

    /**
     * A completed remote Task can be reconciled more than once, including after process death.
     * Reuse the exact previously registered artifact only after re-verifying its durable bytes;
     * never overwrite a changed file or create a second reference for the same remote part.
     */
    private fun importRawArtifact(
        path: FileScopePath,
        bytes: ByteArray,
        sessionId: String,
    ): JsonObject =
        synchronized(ARTIFACT_IMPORT_LOCK) {
            val expectedHash = FileContentStore.sha256Hex(bytes)
            storage.artifacts.findBySessionAndPath(sessionId, path.relativePath)?.let { existing ->
                val file = resolveWorkspaceFile(path)
                require(
                    existing.size == bytes.size.toLong() &&
                        existing.sha256 == expectedHash &&
                        file.isFile &&
                        file.length() == existing.size &&
                        FileContentStore.sha256Hex(file.readBytes()) == existing.sha256,
                ) { "saved A2A Artifact no longer matches the remote Task snapshot" }
                return@synchronized artifactReference(
                    existing.id,
                    existing.sha256,
                    existing.size,
                    existing.mediaType,
                )
            }
            val parent = requireNotNull(resolveWorkspaceFile(path).parentFile) { "A2A Artifact path has no parent" }
            require((parent.isDirectory || parent.mkdirs()) && parent.isDirectory) {
                "A2A Artifact output directory is unavailable"
            }
            val outcome =
                workspace.writeArtifact(
                    path = path,
                    bytes = bytes,
                    region = WorkspaceLayout.OUTPUT,
                    sessionId = sessionId,
                    sink =
                        WorkspaceArtifactStore.ArtifactSink { owner, record ->
                            storage.artifacts.register(
                                record.id,
                                owner,
                                record.relativePath,
                                record.mediaType,
                                record.sizeBytes,
                                record.sha256,
                                resolveWorkspaceFile(FileScopePath(workspaceScopeId, record.relativePath)),
                            )
                        },
                )
            artifactReference(
                outcome.record.id,
                outcome.record.sha256,
                outcome.record.sizeBytes,
                outcome.record.mediaType,
            )
        }

    private fun artifactReference(
        artifactId: String,
        sha256: String,
        size: Long,
        mediaType: String,
    ): JsonObject =
        buildJsonObject {
            put("artifactRef", artifactId)
            put("sha256", sha256)
            put("size", size)
            put("mediaType", mediaType)
            put("trust", UNTRUSTED_MARKER)
        }

    private fun decodeBounded(base64: String): ByteArray {
        require(
            base64.length <= MAX_BASE64_CHARS && base64.matches(BASE64_PATTERN),
        ) { "A2A artifact base64 is invalid" }
        val bytes = Base64.getDecoder().decode(base64)
        require(bytes.size.toLong() <= MAX_ARTIFACT_BYTES) { "A2A remote Artifact exceeds import limit" }
        return bytes
    }

    private fun safeFilename(value: String): String {
        val cleaned = value.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("").take(128)
        return cleaned.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "artifact.bin"
    }

    private fun bearer(agentId: String): String? {
        val alias = storage.a2aAgents.resolve(agentId).authAlias ?: return null
        return storage.secrets.get(
            com.helix.core.model
                .SecretAlias(alias),
        )
    }

    private fun A2aTaskEntity.interfaceSnapshot(): A2aInterfaceSnapshot =
        A2aInterfaceSnapshot(
            endpoint =
                com.helix.core.model.NormalizedEndpoint
                    .parse(interfaceUrl),
            binding = A2aBinding.entries.single { it.wireName == binding },
            protocolVersion = protocolVersion,
            tenant = tenant,
        )

    private fun A2aRemoteTaskState.toPersisted(): A2aPersistedTaskState =
        when (this) {
            A2aRemoteTaskState.SUBMITTED,
            A2aRemoteTaskState.WORKING,
            A2aRemoteTaskState.UNKNOWN,
            -> A2aPersistedTaskState.WORKING

            A2aRemoteTaskState.INPUT_REQUIRED,
            A2aRemoteTaskState.AUTH_REQUIRED,
            -> A2aPersistedTaskState.INPUT_REQUIRED

            A2aRemoteTaskState.COMPLETED,
            A2aRemoteTaskState.DIRECT_MESSAGE,
            -> A2aPersistedTaskState.COMPLETED

            A2aRemoteTaskState.FAILED,
            A2aRemoteTaskState.REJECTED,
            -> A2aPersistedTaskState.FAILED

            A2aRemoteTaskState.CANCELLED -> A2aPersistedTaskState.CANCELLED
        }

    private fun JsonElement?.asBooleanOrFalse(): Boolean = (this as? JsonPrimitive)?.booleanOrNull ?: false

    private fun JsonElement.asString(label: String): String =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: throw IllegalArgumentException("A2A $label must be a string")

    private fun canonical(element: JsonElement): String =
        when (element) {
            is JsonObject -> {
                element.keys.sorted().joinToString(
                    ",",
                    "{",
                    "}",
                ) { key -> "${JsonPrimitive(key)}:${canonical(element.getValue(key))}" }
            }

            is JsonArray -> {
                element.joinToString(",", "[", "]") { canonical(it) }
            }

            is JsonPrimitive -> {
                element.toString()
            }
        }

    private fun String.sha256(): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(toByteArray())
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private companion object {
        const val UNTRUSTED_MARKER = "UNTRUSTED_A2A_CONTENT"
        const val MAX_ARTIFACT_BYTES = 1024L * 1024
        const val MAX_BASE64_CHARS = 1_500_000
        const val POLL_MILLIS = 50L
        val ARTIFACT_IMPORT_LOCK = Any()
        val BASE64_PATTERN = Regex("[A-Za-z0-9+/]*={0,2}")
        val INTERRUPTED_STATES = setOf(A2aRemoteTaskState.INPUT_REQUIRED, A2aRemoteTaskState.AUTH_REQUIRED)
    }
}
