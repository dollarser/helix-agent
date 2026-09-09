package com.helix.feature.browser

import com.helix.tools.browser.ActionOutcome
import com.helix.tools.browser.ActionStatus
import com.helix.tools.browser.BrowserNodeView
import com.helix.tools.browser.ScrollOutcome
import com.helix.tools.browser.ScrollStatus
import com.helix.tools.browser.SensitiveFieldClassifier
import com.helix.tools.browser.SnapshotOutcome
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

internal object BrowserToolResultMapper {
    private val actionJson = Json { ignoreUnknownKeys = true }

    fun successSnapshot(s: com.helix.feature.browser.snapshot.BrowserSnapshot): SnapshotOutcome =
        SnapshotOutcome(
            ok = true,
            tabId = s.tabId,
            url = s.url.text,
            title = s.title.text,
            origin = s.origin,
            navigationGeneration = s.navigationGeneration,
            fingerprint = s.fingerprint,
            truncated = s.truncated,
            nodeCount = s.nodeCount,
            nodes =
                s.nodes.map { n ->
                    BrowserNodeView(
                        index = n.index,
                        role = n.role,
                        text = n.text.text,
                        value = n.value?.text.orEmpty(),
                        href = n.href?.text.orEmpty(),
                        name = n.name?.text.orEmpty(),
                        token = n.token,
                    )
                },
            message = "",
        )

    fun failedSnapshot(
        tabId: String,
        message: String,
    ): SnapshotOutcome =
        SnapshotOutcome(
            ok = false,
            tabId = tabId,
            url = "",
            title = "",
            origin = "",
            navigationGeneration = 0,
            fingerprint = "",
            truncated = false,
            nodeCount = 0,
            nodes = emptyList(),
            message = message,
        )

    /**
     * Maps a fixed action script's raw JSON result to an [ActionOutcome], applying the host
     * sensitive-field re-validation (authoritative, fail-closed over the script's own gate).
     */
    @Suppress("ReturnCount")
    fun mapAction(
        raw: String?,
        nodeIndex: Int,
    ): ActionOutcome {
        if (raw == null) return ActionOutcome(ActionStatus.TIMED_OUT, nodeIndex, "", "", "")
        val obj = parseActionRaw(raw) ?: return ActionOutcome(ActionStatus.ERROR, nodeIndex, "", "", "bad result")
        val tag = obj.str("tag")
        val role = obj.str("role")
        val status = obj.str("status")
        val verdict =
            SensitiveFieldClassifier.classify(
                tag = tag,
                type = obj.str("type"),
                autocomplete = obj.str("autocomplete"),
                nameId = obj.str("nameId"),
                placeholder = obj.str("placeholder"),
            )
        if (verdict is SensitiveFieldClassifier.Verdict.Sensitive) {
            return ActionOutcome(ActionStatus.REFUSED, nodeIndex, tag, role, SensitiveFieldClassifier.reasonOf(verdict))
        }
        return when (status) {
            "performed" -> {
                ActionOutcome(ActionStatus.PERFORMED, nodeIndex, tag, role, "")
            }

            "refused" -> {
                ActionOutcome(
                    ActionStatus.REFUSED,
                    nodeIndex,
                    tag,
                    role,
                    obj.str("reason").ifEmpty { "sensitive-field" },
                )
            }

            "not-a-field" -> {
                ActionOutcome(ActionStatus.REFUSED, nodeIndex, tag, role, "not-a-field")
            }

            "not-found" -> {
                ActionOutcome(ActionStatus.STALE_TOKEN, nodeIndex, tag, role, "not-found")
            }

            "" -> {
                ActionOutcome(ActionStatus.ERROR, nodeIndex, tag, role, "bad result")
            }

            else -> {
                ActionOutcome(ActionStatus.ERROR, nodeIndex, tag, role, status)
            }
        }
    }

    @Suppress("ReturnCount")
    fun mapScroll(
        raw: String?,
        dx: Int,
        dy: Int,
    ): ScrollOutcome {
        if (raw == null) return ScrollOutcome(ScrollStatus.TIMED_OUT, dx, dy, "")
        val obj = parseActionRaw(raw) ?: return ScrollOutcome(ScrollStatus.ERROR, dx, dy, "bad result")
        return if (obj.bool("ok")) {
            ScrollOutcome(ScrollStatus.SCROLLED, dx, dy, "")
        } else {
            ScrollOutcome(ScrollStatus.ERROR, dx, dy, "scroll failed")
        }
    }

    @Suppress("SwallowedException")
    private fun parseActionRaw(raw: String): JsonObject? =
        try {
            actionJson.parseToJsonElement(raw) as? JsonObject
        } catch (e: SerializationException) {
            null
        }

    private fun JsonObject.str(key: String): String =
        this[key]
            ?.jsonPrimitive
            ?.takeIf { it.isString }
            ?.content
            .orEmpty()

    private fun JsonObject.bool(key: String): Boolean = this[key]?.jsonPrimitive?.booleanOrNull ?: false
}
