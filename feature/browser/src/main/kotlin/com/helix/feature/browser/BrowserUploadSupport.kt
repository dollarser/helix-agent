package com.helix.feature.browser

import android.os.Handler
import android.os.Looper
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.feature.browser.snapshot.BrowserUploadScript
import com.helix.feature.browser.snapshot.SnapshotBinder
import com.helix.feature.browser.snapshot.SnapshotResult
import com.helix.feature.browser.snapshot.SnapshotToken
import com.helix.feature.browser.snapshot.TokenVerdict
import com.helix.tools.browser.ActionOutcome
import com.helix.tools.browser.ActionStatus
import com.helix.tools.browser.BrowserUploadOutcome
import com.helix.tools.browser.BrowserUploadRequest
import com.helix.tools.browser.BrowserUploadTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** No file URI or privileged page bridge: one bounded immutable copy enters the approved document. */
internal class BrowserUploadSupport(
    private val controller: BrowserController,
    private val store: WorkspaceArtifactStore,
) {
    private val handler = Handler(Looper.getMainLooper())

    @Suppress("ReturnCount") // Each admission boundary has a distinct refusal.
    fun upload(
        request: BrowserUploadRequest,
        active: () -> Boolean,
    ): BrowserUploadOutcome {
        val index = SnapshotToken.parse(request.token)?.nodeIndex ?: return refused("malformed-token")
        val source =
            try {
                FileScopePath.fromModelReference(request.path)
            } catch (_: IllegalArgumentException) {
                return refused("invalid-source")
            }
        if (!validPageUrl(request.pageUrl)) return refused("invalid-page-url")
        if (!active()) return refused("cancelled-before-disclosure")
        val marker = "__helixUpload_" + UUID.randomUUID().toString().replace("-", "")
        val preflight = evaluate(request, BrowserUploadScript.preflight(index, request.pageUrl, marker), active, false)
        if (preflight.status != ActionStatus.PERFORMED) return BrowserUploadOutcome(preflight)
        val bytes = readBounded(source, active) ?: return refused("source-unavailable-size-limit-or-cancelled")
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (hash != request.sha256) return refused("source-version-changed")
        val script = BrowserUploadScript.attach(index, request.pageUrl, source.name, bytes, marker)
        val action = evaluate(request, script, active, true)
        return BrowserUploadOutcome(action, if (action.status == ActionStatus.PERFORMED) bytes.size else 0)
    }

    private fun validPageUrl(value: String): Boolean {
        val url =
            try {
                URI(value)
            } catch (_: java.net.URISyntaxException) {
                return false
            }
        return url.scheme in setOf("http", "https") && !url.host.isNullOrEmpty() && url.userInfo == null
    }

    @Suppress("TooGenericExceptionCaught") // Storage/cancellation failures never disclose partial bytes.
    private fun readBounded(source: FileScopePath, active: () -> Boolean): ByteArray? =
        try {
            store.openRead(source).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                check(active()) { "inactive" }
                var count = input.read(buffer)
                while (count >= 0) {
                    check(active()) { "inactive" }
                    check(count > 0 && output.size() + count <= BrowserUploadTool.MAX_BYTES) { "size-limit" }
                    output.write(buffer, 0, count)
                    check(active()) { "inactive" }
                    count = input.read(buffer)
                }
                output.toByteArray()
            }
        } catch (_: Exception) {
            null
        }

    private fun matchesSnapshot(
        request: BrowserUploadRequest,
        raw: String?,
    ): Boolean {
        val snapshot =
            runCatching {
                (
                    Json.parseToJsonElement(
                        raw.orEmpty(),
                    ) as? JsonObject
                )?.get("snapshot")
            }.getOrNull()
        val tab = controller.tab(request.tabId) ?: return false
        val rebound = SnapshotBinder.bind(snapshot?.toString(), tab, System.currentTimeMillis())
        return rebound is SnapshotResult.Success &&
            rebound.snapshot.fingerprint == SnapshotToken.parse(request.token)?.fingerprint
    }

    private fun refused(reason: String) = BrowserUploadOutcome(ActionOutcome(ActionStatus.REFUSED, -1, "", "", reason))

    private fun evaluate(
        request: BrowserUploadRequest,
        script: String,
        active: () -> Boolean,
        disclosure: Boolean,
    ): ActionOutcome {
        val pending = PendingUpload()
        handler.post {
            synchronized(pending.done) {
                if (!pending.done.get()) start(request, script, active, disclosure, pending)
            }
        }
        return pending.await(disclosure)
    }

    private fun start(
        request: BrowserUploadRequest,
        script: String,
        active: () -> Boolean,
        disclosure: Boolean,
        pending: PendingUpload,
    ) {
        val tab = controller.tab(request.tabId)
        if (!active() || tab?.url != request.pageUrl ||
            controller.verifyNodeToken(request.tabId, request.token) !is TokenVerdict.Valid
        ) {
            pending.finish(ActionOutcome(ActionStatus.REFUSED, -1, "", "", "inactive-or-page-changed"))
        } else {
            pending.dispatched = true
            controller.evaluateFixed(request.tabId, script) { outcome ->
                pending.finish(mapEvaluation(request, outcome, disclosure))
            }
        }
    }

    private fun mapEvaluation(
        request: BrowserUploadRequest,
        outcome: EvalFixedOutcome,
        disclosure: Boolean,
    ): ActionOutcome =
        when (outcome) {
            EvalFixedOutcome.NoPage -> {
                ActionOutcome(ActionStatus.REFUSED, -1, "", "", "no-page")
            }

            is EvalFixedOutcome.Result -> {
                val mapped = BrowserToolResultMapper.mapAction(outcome.raw, -1)
                val successfulPreflight = !disclosure && mapped.status == ActionStatus.PERFORMED
                when {
                    successfulPreflight && !matchesSnapshot(request, outcome.raw) -> {
                        ActionOutcome(ActionStatus.REFUSED, -1, "", "", "live-dom-changed")
                    }

                    disclosure && mapped.status == ActionStatus.ERROR -> {
                        mapped.copy(status = ActionStatus.UNKNOWN)
                    }

                    else -> {
                        mapped
                    }
                }
            }
        }

    /** Dispatch and timeout share a monitor: a queued callback cannot act after a safe refusal. */
    private class PendingUpload {
        val done = AtomicBoolean(false)
        var dispatched = false
        private val result = AtomicReference<ActionOutcome>()
        private val latch = CountDownLatch(1)

        fun finish(action: ActionOutcome) {
            if (done.compareAndSet(false, true)) {
                result.set(action)
                latch.countDown()
            }
        }

        fun await(disclosure: Boolean): ActionOutcome {
            val completed =
                try {
                    latch.await(7, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    false
                }
            return if (completed) {
                result.get()
            } else {
                synchronized(done) {
                    done.set(true)
                    ActionOutcome(
                        if (disclosure && dispatched) ActionStatus.UNKNOWN else ActionStatus.REFUSED,
                        -1,
                        "",
                        "",
                        "evaluation-interrupted",
                    )
                }
            }
        }
    }
}
