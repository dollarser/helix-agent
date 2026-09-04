package com.helix.extensions.mcp

import com.helix.core.model.NormalizedEndpoint
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.AudioContent
import io.modelcontextprotocol.kotlin.sdk.types.BlobResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.EmbeddedResource
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.InitializeResult
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.ResourceLink
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Dns
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean

internal class SdkMcpClientFacade(
    private val clientName: String,
    private val clientVersion: String,
    private val authenticatedEndpoint: NormalizedEndpoint? = null,
    private val bearerToken: String? = null,
    private val networkPermit: McpNetworkPermit? = null,
    private val httpClientFactory: (NormalizedEndpoint?, String?, McpNetworkPermit?) -> HttpClient = ::newOkHttpClient,
) : McpClientFacade {
    override suspend fun connect(endpointUrl: String): McpClientSession {
        require(endpointUrl.isNotBlank()) { "endpointUrl must not be blank" }

        require((authenticatedEndpoint == null) == (bearerToken == null)) {
            "authenticated endpoint and bearer token must be provided together"
        }
        val httpClient = httpClientFactory(authenticatedEndpoint, bearerToken, networkPermit)
        val sdkClient =
            Client(
                clientInfo = Implementation(name = clientName, version = clientVersion),
                options = ClientOptions(capabilities = ClientCapabilities()),
            )
        val sdkTransport = StreamableHttpClientTransport(client = httpClient, url = endpointUrl)
        val transport = NegotiatedProtocolTransport(sdkTransport)

        var sessionCreated = false
        try {
            sdkClient.connect(transport)
            val serverVersion = checkNotNull(sdkClient.serverVersion) { "MCP server identity missing after initialize" }
            val protocolVersion =
                checkNotNull(transport.negotiatedProtocolVersion) {
                    "MCP protocol version missing after initialize"
                }
            val session =
                SdkMcpClientSession(
                    sdkClient = sdkClient,
                    httpClient = httpClient,
                    capabilities = sdkClient.serverCapabilities ?: ServerCapabilities(),
                    identity =
                        McpServerIdentity(
                            name = serverVersion.name,
                            version = serverVersion.version,
                            negotiatedProtocolVersion = protocolVersion,
                        ),
                )
            sessionCreated = true
            return session
        } finally {
            if (!sessionCreated) {
                withContext(NonCancellable) {
                    sdkClient.close()
                    httpClient.close()
                }
            }
        }
    }
}

/**
 * Captures the initialize result before the SDK's protocol callback consumes it.
 *
 * Kotlin SDK 0.15.0 exposes [StreamableHttpClientTransport.protocolVersion] for request headers
 * but does not populate it after negotiation. Keep that compatibility fix inside the module.
 */
private class NegotiatedProtocolTransport(
    private val delegate: StreamableHttpClientTransport,
) : Transport {
    private var initializeRequestId: RequestId? = null

    var negotiatedProtocolVersion: String? = null
        private set

    override suspend fun start() = delegate.start()

    override suspend fun send(
        message: JSONRPCMessage,
        options: TransportSendOptions?,
    ) {
        if (message is JSONRPCRequest && message.method == INITIALIZE_METHOD) {
            initializeRequestId = message.id
        }
        delegate.send(message, options)
    }

    override suspend fun close() = delegate.close()

    override fun onClose(block: () -> Unit) = delegate.onClose(block)

    override fun onError(block: (Throwable) -> Unit) = delegate.onError(block)

    override fun onMessage(block: suspend (JSONRPCMessage) -> Unit) {
        delegate.onMessage { message ->
            if (message is JSONRPCResponse && message.id == initializeRequestId) {
                val initializeResult = message.result as? InitializeResult
                if (initializeResult != null) {
                    negotiatedProtocolVersion = initializeResult.protocolVersion
                    delegate.protocolVersion = initializeResult.protocolVersion
                }
            }
            block(message)
        }
    }

    private companion object {
        const val INITIALIZE_METHOD = "initialize"
    }
}

private class SdkMcpClientSession(
    private val sdkClient: Client,
    private val httpClient: HttpClient,
    private val capabilities: ServerCapabilities,
    private val identity: McpServerIdentity,
) : McpClientSession {
    private val closed = AtomicBoolean(false)

    override val server: McpServerIdentity
        get() = identity

    override suspend fun ping() {
        check(!closed.get()) { "MCP session is closed" }
        sdkClient.ping()
    }

    override suspend fun snapshotMetadata(limits: McpMetadataLimits): McpMetadataSnapshot {
        check(!closed.get()) { "MCP session is closed" }
        val toolsResult = capabilities.tools?.let { sdkClient.listTools() }
        val resourcesResult = capabilities.resources?.let { sdkClient.listResources() }
        val promptsResult = capabilities.prompts?.let { sdkClient.listPrompts() }

        return McpMetadataSnapshot(
            capabilities = capabilities.toSnapshot(),
            tools = toolsResult?.tools.orEmpty().toToolMetadata(limits),
            resources = resourcesResult?.resources.orEmpty().toResourceMetadata(limits),
            prompts = promptsResult?.prompts.orEmpty().toPromptMetadata(limits),
            toolsHaveMore =
                toolsResult != null &&
                    (toolsResult.nextCursor != null || toolsResult.tools.size > limits.maxItemsPerKind),
            resourcesHaveMore =
                resourcesResult != null &&
                    (resourcesResult.nextCursor != null || resourcesResult.resources.size > limits.maxItemsPerKind),
            promptsHaveMore =
                promptsResult != null &&
                    (promptsResult.nextCursor != null || promptsResult.prompts.size > limits.maxItemsPerKind),
        ).also { snapshot -> snapshot.requireWithinTotalBytes(limits.maxTotalMetadataBytes) }
    }

    override suspend fun callTool(
        name: String,
        arguments: JsonObject,
        limits: McpToolResultLimits,
    ): McpToolResult {
        check(!closed.get()) { "MCP session is closed" }
        require(name.isNotBlank()) { "MCP tool name must not be blank" }
        val result = sdkClient.callTool(name, arguments.mapValues { (_, value) -> value.toSdkValue() })
        require(result.content.size <= limits.maxBlocks) {
            "MCP tool result exceeds ${limits.maxBlocks} blocks"
        }
        val blocks = result.content.map { block -> block.toHelixBlock(limits) }
        val structured = result.structuredContent
        if (structured != null) {
            require(structured.toString().toByteArray(Charsets.UTF_8).size <= limits.maxStructuredBytes) {
                "MCP structured result exceeds ${limits.maxStructuredBytes} bytes"
            }
        }
        val totalBytes =
            blocks.sumOf { it.payloadBytes() } +
                (structured?.toString()?.toByteArray(Charsets.UTF_8)?.size ?: 0)
        require(totalBytes <= limits.maxTotalBytes) {
            "MCP tool result exceeds ${limits.maxTotalBytes} total bytes"
        }
        return McpToolResult(result.isError == true, blocks, structured)
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            sdkClient.close()
        } finally {
            httpClient.close()
        }
    }
}

private fun JsonElement.toSdkValue(): Any? =
    when (this) {
        JsonNull -> {
            null
        }

        is JsonObject -> {
            mapValues { (_, value) -> value.toSdkValue() }
        }

        is JsonArray -> {
            map { it.toSdkValue() }
        }

        is JsonPrimitive -> {
            if (isString) {
                content
            } else {
                booleanOrNull ?: longOrNull ?: doubleOrNull ?: contentOrNull
            }
        }
    }

private fun io.modelcontextprotocol.kotlin.sdk.types.ContentBlock.toHelixBlock(
    limits: McpToolResultLimits,
): McpResultBlock =
    when (this) {
        is TextContent -> {
            McpResultBlock.Text(text.requireBytes("text", limits.maxTextBytes))
        }

        is ImageContent -> {
            McpResultBlock.Image(
                mimeType.requireBytes("image MIME type", MAX_RESULT_LABEL_BYTES),
                data.requireBase64Bytes("image", limits.maxBinaryBytes),
            )
        }

        is AudioContent -> {
            McpResultBlock.Audio(
                mimeType.requireBytes("audio MIME type", MAX_RESULT_LABEL_BYTES),
                data.requireBase64Bytes("audio", limits.maxBinaryBytes),
            )
        }

        is ResourceLink -> {
            McpResultBlock.ResourceLink(
                uri = uri.requireBytes("resource URI", MAX_RESULT_URI_BYTES),
                name = name.requireBytes("resource name", MAX_RESULT_LABEL_BYTES),
                title = title?.requireBytes("resource title", MAX_RESULT_LABEL_BYTES),
                description = description?.requireBytes("resource description", limits.maxTextBytes),
                mimeType = mimeType?.requireBytes("resource MIME type", MAX_RESULT_LABEL_BYTES),
                size = size,
            )
        }

        is EmbeddedResource -> {
            when (val contents = resource) {
                is TextResourceContents -> {
                    McpResultBlock.EmbeddedText(
                        uri = contents.uri.requireBytes("embedded resource URI", MAX_RESULT_URI_BYTES),
                        mimeType = contents.mimeType?.requireBytes("embedded MIME type", MAX_RESULT_LABEL_BYTES),
                        text = contents.text.requireBytes("embedded text", limits.maxTextBytes),
                    )
                }

                is BlobResourceContents -> {
                    McpResultBlock.EmbeddedBlob(
                        uri = contents.uri.requireBytes("embedded resource URI", MAX_RESULT_URI_BYTES),
                        mimeType = contents.mimeType?.requireBytes("embedded MIME type", MAX_RESULT_LABEL_BYTES),
                        base64Data = contents.blob.requireBase64Bytes("embedded blob", limits.maxBinaryBytes),
                    )
                }

                else -> {
                    error("unsupported MCP embedded resource type: ${contents::class.simpleName}")
                }
            }
        }

        else -> {
            error("unsupported MCP result block type: ${this::class.simpleName}")
        }
    }

private fun McpResultBlock.payloadBytes(): Int =
    when (this) {
        is McpResultBlock.Text -> {
            text.toByteArray(Charsets.UTF_8).size
        }

        is McpResultBlock.Image -> {
            mimeType.length + base64Data.length
        }

        is McpResultBlock.Audio -> {
            mimeType.length + base64Data.length
        }

        is McpResultBlock.ResourceLink -> {
            listOfNotNull(uri, name, title, description, mimeType).sumOf { it.toByteArray(Charsets.UTF_8).size }
        }

        is McpResultBlock.EmbeddedText -> {
            uri.toByteArray(Charsets.UTF_8).size +
                (mimeType?.toByteArray(Charsets.UTF_8)?.size ?: 0) +
                text.toByteArray(Charsets.UTF_8).size
        }

        is McpResultBlock.EmbeddedBlob -> {
            uri.length + (mimeType?.length ?: 0) + base64Data.length
        }
    }

private fun String.requireBytes(
    label: String,
    maxBytes: Int,
): String {
    require(toByteArray(Charsets.UTF_8).size <= maxBytes) { "MCP $label exceeds $maxBytes bytes" }
    return this
}

private fun String.requireBase64Bytes(
    label: String,
    maxDecodedBytes: Int,
): String {
    require(length <= ((maxDecodedBytes.toLong() + 2) / 3 * 4)) {
        "MCP $label exceeds $maxDecodedBytes decoded bytes"
    }
    require(matches(BASE64_PATTERN)) { "MCP $label is not canonical base64 data" }
    return this
}

private val BASE64_PATTERN = Regex("(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?")
private const val MAX_RESULT_LABEL_BYTES = 4_096
private const val MAX_RESULT_URI_BYTES = 16_384

private fun newOkHttpClient(
    authenticatedEndpoint: NormalizedEndpoint?,
    bearerToken: String?,
    networkPermit: McpNetworkPermit?,
): HttpClient =
    HttpClient(OkHttp) {
        install(SSE)
        engine {
            // Redirects need the same per-hop origin/DNS/peer checks as HXA-066. The SDK
            // transport does not expose that decision point, so reject rather than risk
            // forwarding a bearer credential to a different origin.
            config {
                followRedirects(false)
                followSslRedirects(false)
                if (networkPermit != null) {
                    proxy(Proxy.NO_PROXY)
                    dns(
                        Dns { hostname ->
                            if (hostname != networkPermit.host) {
                                throw UnknownHostException("MCP transport refused an unexpected host")
                            }
                            networkPermit.pinnedAddresses(hostname)
                        },
                    )
                }
            }
        }
        if (authenticatedEndpoint != null && bearerToken != null) {
            engine {
                addInterceptor { chain ->
                    val request = chain.request()
                    val requestUrl = request.url
                    val sameOrigin =
                        requestUrl.scheme == authenticatedEndpoint.scheme &&
                            requestUrl.host == authenticatedEndpoint.host &&
                            requestUrl.port == authenticatedEndpoint.port
                    val authorizedRequest =
                        if (sameOrigin) {
                            request
                                .newBuilder()
                                .header("Authorization", "Bearer $bearerToken")
                                .build()
                        } else {
                            request.newBuilder().removeHeader("Authorization").build()
                        }
                    chain.proceed(authorizedRequest)
                }
            }
        }
    }
