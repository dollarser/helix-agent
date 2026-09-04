package com.helix.feature.browser.snapshot

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * Node-token minting and validation (HXA-061; doc 09 §3.3: click/type 只能使用最近一次
 * snapshot 返回的短期 node token).
 *
 * A token is a base64url-encoded JSON document carrying its bindings; validity is decided
 * by [validate] against the tab's LIVE state (origin, navigation generation), the host's
 * last successful snapshot fingerprint, and the clock. Nothing the token itself asserts is
 * trusted on its own: a page or a model can read and replay a token, but the host is the
 * sole minter and every binding is re-checked at use time, so a replayed token is stale
 * the moment the page navigates, crosses an origin, changes its DOM (new fingerprint),
 * or outlives its TTL (doc 09 §3.3 失效语义).
 */
object SnapshotToken {
    const val TOKEN_VERSION = 1

    /** doc 09 §3.3 短期 token: one minute of validity by default. */
    const val DEFAULT_TTL_MILLIS = 60_000L

    private val json = Json { ignoreUnknownKeys = false }

    /** Mints the opaque token string for [node]. Deterministic in its inputs. */
    fun mint(node: NodeToken): String {
        val payload =
            buildJsonObject {
                put("version", TOKEN_VERSION)
                put("nodeIndex", node.nodeIndex)
                put("tabId", node.tabId)
                put("origin", node.origin)
                put("navigationGeneration", node.navigationGeneration)
                put("fingerprint", node.fingerprint)
                put("mintedAtMillis", node.mintedAtMillis)
                put("ttlMillis", node.ttlMillis)
            }.toString()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(Charsets.UTF_8))
    }

    /**
     * Parses [token] back to its [NodeToken]. Returns null when it is not a well-formed
     * JSON object of [TOKEN_VERSION] with every required field — the fail-closed answer a
     * replayed / hand-edited / wrong-version token always gets.
     */
    @Suppress("ReturnCount", "SwallowedException")
    fun parse(token: String): NodeToken? {
        val payload: String =
            try {
                String(Base64.getUrlDecoder().decode(token), Charsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                return null
            }
        val element: JsonElement =
            try {
                json.parseToJsonElement(payload)
            } catch (e: SerializationException) {
                return null
            }
        val root: JsonObject = element as? JsonObject ?: return null
        return runCatching {
            NodeToken(
                version = root.reqLong("version").toInt(),
                nodeIndex = root.reqLong("nodeIndex").toInt(),
                tabId = root.reqString("tabId"),
                origin = root.reqString("origin"),
                navigationGeneration = root.reqLong("navigationGeneration"),
                fingerprint = root.reqString("fingerprint"),
                mintedAtMillis = root.reqLong("mintedAtMillis"),
                ttlMillis = root.reqLong("ttlMillis"),
            ).takeIf { it.version == TOKEN_VERSION && it.nodeIndex >= 0 && it.ttlMillis > 0 }
        }.getOrNull()
    }

    private fun JsonObject.reqLong(key: String): Long =
        this[key]?.jsonPrimitive?.long ?: throw IllegalStateException(key)

    private fun JsonObject.reqString(key: String): String =
        this[key]?.jsonPrimitive?.let { if (it.isString) it.content else throw IllegalStateException(key) }
            ?: throw IllegalStateException(key)

    /**
     * Validates [token] against the tab's live [live] state. The checks run in binding
     * order (tab → origin → generation → fingerprint → TTL) and the FIRST failure wins;
     * every outcome except [TokenVerdict.Valid] is a fail-closed refusal.
     */
    @Suppress("ReturnCount")
    fun validate(
        token: NodeToken,
        live: LiveTabState,
        nowMillis: Long,
    ): TokenVerdict {
        if (token.tabId != live.tabId) return TokenVerdict.WrongTab
        if (token.origin != live.origin) return TokenVerdict.StaleOrigin
        if (token.navigationGeneration != live.navigationGeneration) return TokenVerdict.StaleGeneration
        if (token.fingerprint != live.lastSnapshotFingerprint) return TokenVerdict.StaleFingerprint
        if (token.expired(nowMillis)) return TokenVerdict.Expired
        return TokenVerdict.Valid
    }
}

/** The tab's live state at validation time, exactly as the HOST (not the token) sees it. */
data class LiveTabState(
    val tabId: String,
    val origin: String,
    val navigationGeneration: Long,
    val lastSnapshotFingerprint: String?,
)

/** One fail-closed outcome per binding (doc 09 §3.3 失效语义). */
sealed interface TokenVerdict {
    data object Valid : TokenVerdict

    /** Not a parseable [SnapshotToken.TOKEN_VERSION] token. */
    data object MalformedToken : TokenVerdict

    /** Bound to a different (or closed) tab. */
    data object WrongTab : TokenVerdict

    /** The tab's page crossed an origin since minting. */
    data object StaleOrigin : TokenVerdict

    /** The tab committed a newer navigation since minting. */
    data object StaleGeneration : TokenVerdict

    /** The DOM changed: the host's last snapshot fingerprint no longer matches. */
    data object StaleFingerprint : TokenVerdict

    /** The token outlived its TTL. */
    data object Expired : TokenVerdict
}
