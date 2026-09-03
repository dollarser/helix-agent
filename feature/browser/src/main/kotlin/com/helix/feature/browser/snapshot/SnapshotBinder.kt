package com.helix.feature.browser.snapshot

import com.helix.feature.browser.BrowserTab
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Turns the fixed script's raw JSON result into a [BrowserSnapshot] (HXA-061). Pure JVM,
 * so every fail-closed branch is unit-testable without a WebView.
 *
 * The page's document is UNTRUSTED (doc 09 §3.4): the script runs in the page's JS context
 * and a hostile page can corrupt, truncate or lie about its own DOM. The script's bounds
 * are therefore NOT a trust boundary — [bind] re-verifies every field, every bound and the
 * node indices, and fails closed on any drift. Every structural access uses a safe cast so
 * a type violation is a fail-closed [SnapshotResult.Failed], never a thrown exception.
 * The fingerprint and the node tokens are minted HERE, in the trusted host, never by the
 * page, so a page cannot forge them.
 */
object SnapshotBinder {
    private val json = Json { ignoreUnknownKeys = false }

    /**
     * Binds [rawResult] (the raw JSON text the fixed script evaluated to) for [tab] at
     * [clockMillis]. Returns [SnapshotResult.Success] only when the payload is a
     * well-formed, in-bounds tree; every other input is a fail-closed [SnapshotResult.Failed].
     */
    @Suppress("ReturnCount", "SwallowedException")
    fun bind(
        rawResult: String?,
        tab: BrowserTab,
        clockMillis: Long,
    ): SnapshotResult {
        if (rawResult.isNullOrBlank()) return SnapshotResult.Failed(SnapshotFailure.NO_RESULT)

        val root =
            try {
                json.parseToJsonElement(rawResult)
            } catch (e: SerializationException) {
                return SnapshotResult.Failed(SnapshotFailure.UNPARSEABLE_RESULT)
            }.asObject() ?: return SnapshotResult.Failed(SnapshotFailure.UNPARSEABLE_RESULT)

        // A missing or non-numeric `v` is a structural malformation (the payload is a JSON
        // object but lacks a usable version field); a present-but-wrong number is a version
        // mismatch. Both are fail-closed refusals.
        val version =
            root["v"]?.asPrimitive()?.longOrNull
                ?: return SnapshotResult.Failed(SnapshotFailure.MALFORMED_RESULT)
        if (version != BrowserSnapshotScript.SCRIPT_VERSION.toLong()) {
            return SnapshotResult.Failed(SnapshotFailure.VERSION_MISMATCH)
        }

        val truncated =
            root["truncated"]?.asPrimitive()?.booleanOrNull
                ?: return SnapshotResult.Failed(SnapshotFailure.MALFORMED_RESULT)

        val nodesElement = root["nodes"]?.asArray() ?: return SnapshotResult.Failed(SnapshotFailure.MALFORMED_RESULT)

        if (nodesElement.size > BrowserSnapshotScript.MAX_NODES) {
            return SnapshotResult.Failed(SnapshotFailure.OVER_BUDGET)
        }

        val origin = BrowserOrigin.of(tab.url) ?: return SnapshotResult.Failed(SnapshotFailure.NO_ORIGIN)
        val fingerprint = fingerprintOf(nodesElement, truncated)

        val nodes = ArrayList<SnapshotNode>(nodesElement.size)
        for ((position, element) in nodesElement.withIndex()) {
            val node =
                bindNode(
                    obj = element.asObject(),
                    expectedIndex = position,
                    tabId = tab.id,
                    origin = origin,
                    navigationGeneration = tab.navigationGeneration,
                    fingerprint = fingerprint,
                    clockMillis = clockMillis,
                ) ?: return SnapshotResult.Failed(SnapshotFailure.MALFORMED_RESULT)
            nodes.add(node)
        }

        return SnapshotResult.Success(
            BrowserSnapshot(
                tabId = tab.id,
                url = UntrustedWebContent(tab.url),
                title = UntrustedWebContent(tab.title.orEmpty()),
                origin = origin,
                navigationGeneration = tab.navigationGeneration,
                fingerprint = fingerprint,
                mintedAtMillis = clockMillis,
                truncated = truncated,
                nodeCount = nodes.size,
                nodes = nodes,
            ),
        )
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun bindNode(
        obj: JsonObject?,
        expectedIndex: Int,
        tabId: String,
        origin: String,
        navigationGeneration: Long,
        fingerprint: String,
        clockMillis: Long,
    ): SnapshotNode? {
        if (obj == null) return null

        val index = obj["i"]?.asPrimitive()?.longOrNull?.toInt() ?: return null
        if (index != expectedIndex) return null

        val tag = obj["tag"]?.asPrimitive()?.takeIf { it.isString }?.content
        if (tag == null || !TAG_PATTERN.matches(tag)) return null

        val role = obj["role"]?.asPrimitive()?.takeIf { it.isString }?.content
        if (role == null || role !in VALID_ROLES) return null

        val text =
            when (val r = readString(obj["text"], required = true)) {
                is StringRead.Ok -> r.value ?: return null
                StringRead.Rejected -> return null
            }
        val value =
            when (val r = readString(obj["value"], required = false)) {
                is StringRead.Ok -> r.value
                StringRead.Rejected -> return null
            }
        val href =
            when (val r = readString(obj["href"], required = false)) {
                is StringRead.Ok -> r.value
                StringRead.Rejected -> return null
            }
        val name =
            when (val r = readString(obj["name"], required = false)) {
                is StringRead.Ok -> r.value
                StringRead.Rejected -> return null
            }

        val token =
            SnapshotToken.mint(
                NodeToken(
                    version = SnapshotToken.TOKEN_VERSION,
                    nodeIndex = index,
                    tabId = tabId,
                    origin = origin,
                    navigationGeneration = navigationGeneration,
                    fingerprint = fingerprint,
                    mintedAtMillis = clockMillis,
                    ttlMillis = SnapshotToken.DEFAULT_TTL_MILLIS,
                ),
            )

        return SnapshotNode(
            index = index,
            tag = tag,
            role = role,
            text = UntrustedWebContent(text),
            value = value?.let { UntrustedWebContent(it) },
            href = href?.let { UntrustedWebContent(it) },
            name = name?.let { UntrustedWebContent(it) },
            token = token,
        )
    }

    /**
     * Reads one string field, fail-closed. A field is valid when it is a present, in-bounds
     * string; optional fields are ALSO valid when absent or JSON `null` (the fixed script
     * emits `"value": null` for password / empty fields — a legitimate null, not a
     * malformation). A present value that is not a string, or that exceeds
     * [BrowserSnapshotScript.MAX_TEXT_LENGTH], is [StringRead.Rejected] so the caller fails
     * the whole snapshot closed.
     */
    @Suppress("ReturnCount")
    private fun readString(
        element: kotlinx.serialization.json.JsonElement?,
        required: Boolean,
    ): StringRead {
        if (element == null || element is JsonNull) {
            return if (required) StringRead.Rejected else StringRead.Ok(null)
        }
        val primitive = element.asPrimitive() ?: return StringRead.Rejected
        if (!primitive.isString) return StringRead.Rejected
        val content = primitive.content
        if (content.length > BrowserSnapshotScript.MAX_TEXT_LENGTH) return StringRead.Rejected
        return StringRead.Ok(content)
    }

    /**
     * One string-field read: [Ok] carries the value (null = a legitimately-null optional);
     * [Rejected] is fail-closed.
     */
    private sealed class StringRead {
        data class Ok(
            val value: String?,
        ) : StringRead()

        data object Rejected : StringRead()
    }

    /**
     * SHA-256 over a length-prefixed canonical encoding of the whole tree, so the
     * fingerprint is unambiguous (field values may contain any byte) and deterministic.
     * Computed over the RAW array the page produced — the same canonical values the tokens
     * bind to — and captured before host token minting.
     */
    private fun fingerprintOf(
        nodes: JsonArray,
        truncated: Boolean,
    ): String {
        val out = ByteArrayOutputStream()

        fun writeInt(i: Int) {
            // Big-endian 4 bytes, unambiguous for the length / index values used here.
            out.write((i ushr 24) and 0xFF)
            out.write((i ushr 16) and 0xFF)
            out.write((i ushr 8) and 0xFF)
            out.write(i and 0xFF)
        }

        fun writeStr(s: String?) {
            if (s == null) {
                out.write(0)
                return
            }
            out.write(1)
            val bytes = s.toByteArray(StandardCharsets.UTF_8)
            writeInt(bytes.size)
            out.write(bytes)
        }

        out.write(if (truncated) 1 else 0)
        writeInt(nodes.size)
        for (element in nodes) {
            val obj = element.asObject() ?: continue
            writeInt(obj["i"]?.asPrimitive()?.longOrNull?.toInt() ?: -1)
            writeStr(obj["tag"]?.asPrimitive()?.takeIf { it.isString }?.content)
            writeStr(obj["role"]?.asPrimitive()?.takeIf { it.isString }?.content)
            writeStr(obj["text"]?.asPrimitive()?.takeIf { it.isString }?.content)
            writeStr(obj["value"]?.asPrimitive()?.takeIf { it.isString }?.content)
            writeStr(obj["href"]?.asPrimitive()?.takeIf { it.isString }?.content)
            writeStr(obj["name"]?.asPrimitive()?.takeIf { it.isString }?.content)
        }
        return MessageDigest.getInstance("SHA-256").digest(out.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private val TAG_PATTERN = Regex("[a-z][a-z0-9-]{0,63}")

    private val VALID_ROLES =
        setOf("link", "button", "field", "image", "heading", "interactive")
}

// Safe structural casts: a wrong-typed field is a fail-closed null, never a thrown cast.
private fun kotlinx.serialization.json.JsonElement.asObject(): JsonObject? = this as? JsonObject

private fun kotlinx.serialization.json.JsonElement.asArray(): JsonArray? = this as? JsonArray

private fun kotlinx.serialization.json.JsonElement.asPrimitive(): JsonPrimitive? = this as? JsonPrimitive

/** One fail-closed outcome per binder guard. */
enum class SnapshotFailure {
    /** The tab has no committed page / no live WebView to evaluate. */
    NO_PAGE,

    /** No result came back (the evaluate was cancelled or produced nothing). */
    NO_RESULT,

    /** The raw payload is not a JSON object. */
    UNPARSEABLE_RESULT,

    /** The payload's version does not match the fixed script's [BrowserSnapshotScript.SCRIPT_VERSION]. */
    VERSION_MISMATCH,

    /** The tree exceeds [BrowserSnapshotScript.MAX_NODES] despite the script's own cap. */
    OVER_BUDGET,

    /** A field, role, tag or node index violated the host's re-validation. */
    MALFORMED_RESULT,

    /** The tab URL has no derivable origin (should be impossible for a policy-allowed URL). */
    NO_ORIGIN,
}

/** The controller-facing outcome of a snapshot request. */
sealed interface SnapshotResult {
    data class Success(
        val snapshot: BrowserSnapshot,
    ) : SnapshotResult

    data class Failed(
        val failure: SnapshotFailure,
    ) : SnapshotResult
}
