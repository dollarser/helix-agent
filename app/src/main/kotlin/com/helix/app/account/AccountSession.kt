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
                throw IOException("Invalid account response or storage failure")
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
    ): WireClient {
        validateTarget(endpoint)
        return AccountServiceWire(this, endpoint, delegate)
    }

    internal suspend fun authorization(
        failed: String? = null,
        expectedRevision: Long,
    ): AccountTokens? =
        withContext(Dispatchers.IO) {
            gate.withLock {
                if (expectedRevision != revision) {
                    if (tokens == null && !blocked) return@withLock null
                    throw IOException("Account changed")
                }
                load()
                if (blocked) throw IOException("Account storage unavailable")
                var current = tokens ?: return@withLock null
                if (current.refreshExpires <= now()) {
                    clear()
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
                        clear()
                        throw cancelled
                    } catch (rejected: AccountRequestFailure) {
                        clear()
                        if (rejected.status == 401) return@withLock null
                        throw IOException("Account refresh rejected")
                    } catch (_: Exception) {
                        clear()
                        throw IOException("Account refresh failed; sign in again")
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
            publishIdentity()
        } catch (error: Exception) {
            blocked = true
            throw IOException("Account storage unavailable")
        }
    }

    private fun publishIdentity() {
        mutableState.value = tokens != null
        mutableIdentity.value = identity
    }

    private fun clear() {
        sessionRevision.incrementAndGet()
        tokens = null
        loaded = true
        publishIdentity()
        blocked = true
        storage.clear()
        blocked = false
    }

    private suspend fun exchange(
        operation: String,
        fields: Map<String, String>,
    ): ByteArray =
        kotlinx.coroutines.withTimeout(30000) {
            val body = buildJsonObject { fields.forEach { (key, value) -> put(key, value) } }.toString().toByteArray()
            val response =
                auth.open(
                    WireRequest(
                        "POST",
                        "$AUTH/auth/$operation",
                        mapOf(
                            "X-Client-Id" to CLIENT_ID,
                            "Content-Type" to "application/json",
                            "User-Agent" to AGENT,
                        ),
                        body,
                    ),
                )
            try {
                if (response.status !in 200..299) throw AccountRequestFailure(response.status)
                response.body.bytes().also { require(it.size <= 65536) }
            } finally {
                response.body.close()
            }
        }

    companion object {
        const val AUTH = "https://accounts.sunlingzhang.com"
        const val CLIENT_ID = "helix-android-preview-v1"
        const val AGENT = "android-ai-client/1.0"
        private val HOSTS =
            setOf("accounts.sunlingzhang.com", "api.sunlingzhang.com", "asr.sunlingzhang.com", "tts.sunlingzhang.com")

        fun validateTarget(endpoint: String) {
            val uri = URI(endpoint)
            require(uri.scheme == "https" && uri.host in HOSTS && uri.port in setOf(-1, 443))
            require(uri.userInfo == null && uri.fragment == null && uri.query == null)
        }
    }
}

/** Exact target, one pre-body 401 refresh retry, and identity checks on all late body delivery. */
private class AccountServiceWire(
    private val account: AccountSession,
    private val endpoint: String,
    private val delegate: WireClient,
) : WireClient {
    private val revision = account.revision
    private val boundIdentity =
        java.util.concurrent.atomic
            .AtomicReference<String?>(null)

    // No account and refresh rejection must return local 401 before any service body is emitted.
    @Suppress("ReturnCount")
    override suspend fun open(request: WireRequest): WireResponse {
        require(request.url == endpoint)
        require(
            request.headers.keys.none {
                it.lowercase() in
                    setOf("authorization", "x-client-id", "cookie", "proxy-authorization")
            },
        )
        val current = account.authorization(expectedRevision = revision) ?: return signedOut()
        boundIdentity.compareAndSet(null, current.identity)
        if (boundIdentity.get() != current.identity) throw IOException("Account changed")
        var response = send(request, current)
        if (response.status == 401) {
            response.body.close()
            val next = account.authorization(current.access, revision) ?: return signedOut()
            if (next.identity != current.identity) throw IOException("Account changed")
            response = send(request, next)
        }
        val body = response.body

        fun checkIdentity() {
            if (account.revision != revision || account.identity != current.identity) {
                body.close()
                throw IOException("Account changed")
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

    private suspend fun send(
        request: WireRequest,
        current: AccountTokens,
    ): WireResponse =
        delegate.open(
            WireRequest(
                request.method,
                endpoint,
                request.headers.filterKeys { !it.equals("User-Agent", true) } +
                    mapOf(
                        "Authorization" to "Bearer ${current.access}",
                        "X-Client-Id" to AccountSession.CLIENT_ID,
                        "User-Agent" to AccountSession.AGENT,
                    ),
                request.body,
            ),
        )

    private fun signedOut() =
        WireResponse(
            401,
            emptyMap(),
            object : WireBody {
                override suspend fun bytes() = ByteArray(0)

                override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) = Unit

                override fun close() = Unit
            },
        )
}

private class AccountRequestFailure(
    val status: Int,
) : IOException("Account request failed: HTTP $status")
