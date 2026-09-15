package com.helix.runtime.cli.app

import com.helix.core.model.ModelErrorCode
import com.helix.runtime.cli.client.CliModelCatalog
import com.helix.runtime.cli.client.CliModelCatalogCodec
import com.helix.runtime.cli.client.CliModelInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The authenticated catalog stays in the Runtime; only selected public model metadata crosses IPC. */
internal class CodexModelCatalog(
    private val vault: CliSubscriptionCredentialVault,
    private val oauth: CodexLoginController,
) {
    fun fetch(): CliModelCatalog {
        val client =
            OkHttpClient
                .Builder()
                .dns(BoundedDnsCache())
                .callTimeout(15, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .followRedirects(false)
                .build()
        return try {
            var result = execute(client)
            // A logged-out vault must stay a terminal AUTH state: refreshing without a stored
            // session throws from vault.load and the catch below would misclassify it PROTOCOL.
            if (result is CliModelCatalog.Failed && result.code == ModelErrorCode.AUTH &&
                vault.contains(CliSubscriptionProvider.CODEX)
            ) {
                oauth.refresh()
                result = execute(client)
            }
            result
        } catch (failure: IOException) {
            android.util.Log.w("HelixSubscriptionIo", "phase=catalog reason=${failure.javaClass.simpleName}")
            CliModelCatalog.Failed(ModelErrorCode.TRANSPORT, true)
        } catch (_: IllegalArgumentException) {
            CliModelCatalog.Failed(ModelErrorCode.PROTOCOL, false)
        } catch (_: IllegalStateException) {
            CliModelCatalog.Failed(ModelErrorCode.AUTH, false)
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
        }
    }

    private fun execute(client: OkHttpClient): CliModelCatalog {
        if (!vault.contains(CliSubscriptionProvider.CODEX)) return CliModelCatalog.Failed(ModelErrorCode.AUTH, false)
        val session = vault.load(CliSubscriptionProvider.CODEX)
        val request =
            Request
                .Builder()
                .url("${CodexSubscriptionSmoke.MODELS_URL}?client_version=${CodexSubscriptionSmoke.CLIENT_VERSION}")
                .header("Authorization", "Bearer ${session.accessToken}")
                .header("chatgpt-account-id", requireNotNull(session.accountId))
                .header("originator", "codex_cli_rs")
                .header("Accept", "application/json")
                .build()
        return client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                parse(response.body.source().readBoundedByteArray(CodexSubscriptionSmoke.MAX_CATALOG_BYTES))
            } else {
                android.util.Log.w("HelixSubscriptionIo", "phase=catalog httpStatus=${response.code}")
                CliModelCatalog.Failed(
                    when (response.code) {
                        401, 403 -> ModelErrorCode.AUTH
                        429 -> ModelErrorCode.RATE_LIMITED
                        in 500..599 -> ModelErrorCode.SERVER_ERROR
                        else -> ModelErrorCode.HTTP_ERROR
                    },
                    response.code == 429 || response.code >= 500,
                )
            }
        }
    }

    companion object {
        fun parse(bytes: ByteArray): CliModelCatalog.Listed {
            require(bytes.size <= CodexSubscriptionSmoke.MAX_CATALOG_BYTES)
            val root = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
            val rows = requireNotNull(root["models"] as? JsonArray) { "missing models" }
            require(rows.size <= 1024)
            val models =
                rows
                    .mapNotNull { element ->
                        val row = requireNotNull(element as? JsonObject) { "invalid model" }
                        val visibility = row["visibility"]?.jsonPrimitive?.contentOrNull
                        if (visibility in setOf("hide", "none")) return@mapNotNull null
                        val id = row["slug"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val efforts =
                            (row["supported_reasoning_levels"] as? JsonArray)
                                ?.mapNotNull {
                                    (it as? JsonObject)?.get("effort")?.jsonPrimitive?.contentOrNull?.takeIf { value ->
                                        CliModelInfo.validEffort(value)
                                    }
                                }?.distinct()
                        val modalities = (row["input_modalities"] as? JsonArray)?.map { it.jsonPrimitive.content }
                        val context = row["context_window"]?.jsonPrimitive?.longOrNull?.takeIf { it in 1..10_000_000L }
                        CliModelInfo(id, modalities?.contains("image"), efforts, context)
                    }.distinctBy { it.id }
            require(models.size in 1..CliModelCatalogCodec.MAX_MODELS)
            return CliModelCatalog.Listed(models)
        }
    }
}
