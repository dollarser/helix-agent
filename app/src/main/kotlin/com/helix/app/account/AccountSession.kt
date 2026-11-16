package com.helix.app.account

import com.helix.provider.api.wire.OkHttpWireClient
import com.helix.provider.api.wire.WireBody
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI

/** One account owner. Refresh and mutations serialize; generation bodies never automatically replay.
 * The credential boundary sanitizes vendor Keystore/parser failures without secret-bearing causes.
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
class AccountSession(
    private val storage: AccountTokenStorage,
    private val auth: WireClient =
        OkHttpWireClient(followRedirects = false, maxBodyBytes = 65536),
    private val now: () -> Long = System::currentTimeMillis,
) {
    internal val cooldown = AccountRequestCooldown { System.nanoTime() / 1_000_000 }
    private val gate = Mutex()
    private val sessionRevision =
        java.util.concurrent.atomic
            .AtomicLong()
    internal val revision get() = sessionRevision.get()

    @Volatile private var tokens: AccountTokens? = null

    @Volatile private var loaded = false

    @Volatile private var blocked = false
    private val mutableState = MutableStateFlow(false)
    val signedIn = mutableState.asStateFlow()
    private val mutableStatus = MutableStateFlow(AccountStatus.SIGNED_OUT)
    val status = mutableStatus.asStateFlow()
    private val mutableIdentity = MutableStateFlow("signed-out")
    internal val identityState = mutableIdentity.asStateFlow()
    val identity: String get() = tokens?.identity ?: "signed-out"

    suspend fun restore() = withContext(Dispatchers.IO) { gate.withLock { load() } }

    suspend fun login(
        email: String,
        password: String,
    ) = withContext(Dispatchers.IO) {
        require(email.isNotBlank() && email.length <= 320 && password.isNotBlank() && password.length <= 4096)
        gate.withLock {
            val payload = exchange("login", mapOf("username" to email, "password" to password))
            try {
                val next = AccountTokens.response(payload, now())
                storage.save(next)
                sessionRevision.incrementAndGet()
                tokens = next
                loaded = true
                blocked = false
                publishIdentity()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                clear()
                throw cancelled
            } catch (_: Exception) {
                clear()
                throw AccountDataFailure()
            }
        }
    }

    suspend fun register(
        email: String,
        password: String,
    ) = withContext(Dispatchers.IO) {
        require(email.isNotBlank() && email.length <= 320 && password.isNotBlank() && password.length <= 4096)
        gate.withLock {
            exchange("register", mapOf("email" to email, "password" to password))
            Unit
        }
    }

    suspend fun logout() =
        withContext(Dispatchers.IO) {
            gate.withLock {
                load()
                val previous = tokens
                // Revoke local access before the network wait, including all old response consumers.
                clear()
                if (previous != null) exchange("logout", mapOf("refresh_token" to previous.refresh))
                Unit
            }
        }

    fun wire(
        endpoint: String,
        delegate: WireClient = OkHttpWireClient(followRedirects = false),
        expectedRevision: Long = revision,
    ): WireClient {
        validateTarget(endpoint)
        return AccountServiceWire(this, endpoint, delegate, expectedRevision)
    }

    internal suspend fun authorization(
        failed: String? = null,
        expectedRevision: Long,
    ): AccountTokens? =
        withContext(Dispatchers.IO) {
            gate.withLock {
                if (expectedRevision != revision) {
                    if (tokens == null && !blocked) return@withLock null
                    throw com.helix.provider.api.wire.WireRequestStateException(
                        com.helix.provider.api.wire.WireRequestStateException.Reason.ACCOUNT_CHANGED,
                    )
                }
                load()
                if (blocked) throw AccountDataFailure()
                var current = tokens ?: return@withLock null
                if (current.refreshExpires <= now()) {
                    clear(AccountStatus.REAUTH_REQUIRED)
                    return@withLock null
                }
                if (current.expires <= now() + 30000 || failed == current.access) {
                    try {
                        current =
                            AccountTokens.response(
                                exchange("refresh", mapOf("refresh_token" to current.refresh)),
                                now(),
                                current.identity,
                            )
                        storage.save(current)
                        tokens = current
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        clear(AccountStatus.REAUTH_REQUIRED)
                        throw cancelled
                    } catch (rejected: AccountRequestFailure) {
                        clear(AccountStatus.REAUTH_REQUIRED)
                        throw rejected
                    } catch (network: IOException) {
                        clear(AccountStatus.REAUTH_REQUIRED)
                        // A rotating refresh may have been consumed even if its response was lost.
                        throw network
                    } catch (_: Exception) {
                        clear(AccountStatus.REAUTH_REQUIRED)
                        throw AccountDataFailure()
                    }
                }
                current
            }
        }

    private fun load() {
        if (loaded) return
        loaded = true
        try {
            tokens = storage.load()
            if (tokens?.refreshExpires?.let { it <= now() } == true) {
                clear(AccountStatus.REAUTH_REQUIRED)
            } else {
                publishIdentity()
            }
        } catch (error: Exception) {
            blocked = true
            mutableStatus.value = AccountStatus.UNAVAILABLE
            throw AccountDataFailure()
        }
    }

    private fun publishIdentity() {
        mutableState.value = tokens != null
        mutableStatus.value = if (tokens != null) AccountStatus.SIGNED_IN else AccountStatus.SIGNED_OUT
        mutableIdentity.value = identity
    }

    private fun clear(status: AccountStatus = AccountStatus.SIGNED_OUT) {
        sessionRevision.incrementAndGet()
        tokens = null
        loaded = true
        publishIdentity()
        mutableStatus.value = status
        blocked = true
        storage.clear()
        blocked = false
    }

    private suspend fun exchange(
        operation: String,
        fields: Map<String, String>,
    ): ByteArray =
        kotlinx.coroutines.withTimeoutOrNull(30000) {
            val endpoint = "${OfficialServices.ACCOUNT_BASE_URL}/auth/$operation"
            val scope = if (operation in setOf("login", "register")) -1L else revision
            cooldown.check(scope, endpoint)
            val body = buildJsonObject { fields.forEach { (key, value) -> put(key, value) } }.toString().toByteArray()
            val response =
                auth.open(
                    WireRequest(
                        "POST",
                        endpoint,
                        mapOf(
                            "X-Client-Id" to CLIENT_ID,
                            "Content-Type" to "application/json",
                        ),
                        body,
                    ),
                )
            try {
                if (response.status !in 200..299) {
                    val details =
                        com.helix.provider.api.wire
                            .readHttpFailure(response)
                    cooldown.record(scope, endpoint, details)
                    throw AccountRequestFailure(details)
                }
                response.body.bytes().also { require(it.size <= 65536) }
            } finally {
                response.body.close()
            }
        }

            ?: throw java.net.SocketTimeoutException("Account response timeout")

    companion object {
        const val CLIENT_ID = "helix-android-preview-v1"
        private val HOSTS =
            setOf(URI(OfficialServices.ACCOUNT_BASE_URL).host, URI(OfficialServices.INFERENCE_BASE_URL).host)

        fun validateTarget(endpoint: String) {
            val uri = URI(endpoint)
            require(uri.scheme == "https" && uri.host in HOSTS && uri.port in setOf(-1, 443))
            require(
                uri.userInfo == null && uri.fragment == null && (uri.query == null || isBuiltInCatalogUrl(endpoint)),
            )
        }
    }
}

/** Exact target, one pre-body 401 refresh retry, and identity checks on all late body delivery. */
private class AccountServiceWire(
    private val account: AccountSession,
    private val endpoint: String,
    private val delegate: WireClient,
    private val revision: Long,
) : WireClient {
    private val boundIdentity =
        java.util.concurrent.atomic
            .AtomicReference<String?>(null)

    // No account and refresh rejection must return local 401 before any service body is emitted.
    @Suppress("ReturnCount")
    override suspend fun open(request: WireRequest): WireResponse {
        validateRequest(request)
        val current =
            try {
                account.authorization(expectedRevision = revision)
            } catch (failure: AccountRequestFailure) {
                return errorResponse(failure.details)
            } ?: return errorResponse(401)
        try {
            account.cooldown.check(revision, endpoint)
        } catch (failure: AccountRequestFailure) {
            return errorResponse(failure.details)
        }
        boundIdentity.compareAndSet(null, current.identity)
        if (boundIdentity.get() != current.identity) {
            throw com.helix.provider.api.wire.WireRequestStateException(
                com.helix.provider.api.wire.WireRequestStateException.Reason.ACCOUNT_CHANGED,
            )
        }
        val logicalRequest =
            WireRequest(
                request.method,
                request.url,
                com.helix.provider.api.wire.RequestDiagnostics
                    .headers(request.headers),
                request.body,
            )
        var response = send(logicalRequest, current)
        if (response.status == 401) {
            response.body.close()
            val next =
                try {
                    account.authorization(current.access, revision)
                } catch (failure: AccountRequestFailure) {
                    return errorResponse(failure.details)
                } ?: return errorResponse(401)
            if (next.identity != current.identity) {
                throw com.helix.provider.api.wire.WireRequestStateException(
                    com.helix.provider.api.wire.WireRequestStateException.Reason.ACCOUNT_CHANGED,
                )
            }
            response = send(logicalRequest, next)
        }
        response = retainCooldown(response)
        val body = response.body

        fun checkIdentity() {
            if (account.revision != revision || account.identity != current.identity) {
                body.close()
                throw com.helix.provider.api.wire.WireRequestStateException(
                    com.helix.provider.api.wire.WireRequestStateException.Reason.ACCOUNT_CHANGED,
                )
            }
        }
        checkIdentity()
        return WireResponse(
            response.status,
            response.headers,
            object : WireBody {
                override suspend fun bytes(): ByteArray = body.bytes().also { checkIdentity() }

                override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) =
                    body.forEachChunk {
                        checkIdentity()
                        onChunk(it)
                    }

                override fun close() = body.close()
            },
        )
    }

    private fun validateRequest(request: WireRequest) {
        require(request.url == endpoint)
        require(
            request.headers.keys.none {
                it.lowercase() in
                    setOf("authorization", "x-client-id", "cookie", "proxy-authorization")
            },
        )
    }

    private suspend fun retainCooldown(response: WireResponse): WireResponse {
        if (response.status != 429) return response
        val details =
            try {
                com.helix.provider.api.wire
                    .readHttpFailure(response)
            } finally {
                response.body.close()
            }
        account.cooldown.record(revision, endpoint, details)
        return errorResponse(details)
    }

    private suspend fun send(
        request: WireRequest,
        current: AccountTokens,
    ): WireResponse =
        delegate.open(
            WireRequest(
                request.method,
                endpoint,
                request.headers +
                    mapOf(
                        "Authorization" to "Bearer ${current.access}",
                        "X-Client-Id" to AccountSession.CLIENT_ID,
                    ),
                request.body,
            ),
        )

    private fun errorResponse(status: Int) =
        errorResponse(
            com.helix.provider.api.wire
                .HttpFailureDetails(status),
        )

    private fun errorResponse(details: com.helix.provider.api.wire.HttpFailureDetails): WireResponse {
        val bytes =
            details.detail?.let { detail ->
                kotlinx.serialization.json
                    .buildJsonObject {
                        put("detail", kotlinx.serialization.json.JsonPrimitive(detail))
                    }.toString()
                    .toByteArray()
            } ?: ByteArray(0)
        return WireResponse(
            details.status,
            buildMap {
                details.requestId?.let { put("X-Request-ID", listOf(it)) }
                details.retryAfterMillis?.let {
                    put(
                        "Retry-After",
                        listOf(
                            (
                                it / 1000 +
                                    if (it % 1000 ==
                                        0L
                                    ) {
                                        0
                                    } else {
                                        1
                                    }
                            ).toString(),
                        ),
                    )
                }
            },
            object : WireBody {
                override suspend fun bytes() = bytes

                override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) {
                    onChunk(bytes)
                }

                override fun close() = Unit
            },
        )
    }
}

internal class AccountRequestFailure(
    val details: com.helix.provider.api.wire.HttpFailureDetails,
) : IOException("Account request failed: HTTP ${details.status}") {
    constructor(status: Int) : this(
        com.helix.provider.api.wire
            .HttpFailureDetails(status),
    )

    val status: Int get() = details.status
}

/** Sanitized local response/storage failure; never mislabeled as a network outage. */
internal class AccountDataFailure :
    com.helix.provider.api.wire.WireRequestStateException(
        com.helix.provider.api.wire.WireRequestStateException.Reason.ACCOUNT_UNAVAILABLE,
    )
