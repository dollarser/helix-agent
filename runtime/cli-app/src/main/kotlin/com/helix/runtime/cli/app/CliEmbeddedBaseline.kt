package com.helix.runtime.cli.app

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object CliEmbeddedBaseline {
    private const val LOCK_ASSET = "cli/cli-runtime-lock.json"
    private val json = Json { isLenient = false }

    fun lock(context: Context): CliRuntimeLock =
        context.assets
            .open(LOCK_ASSET)
            .bufferedReader(Charsets.UTF_8)
            .use { CliRuntimeLockCodec.parse(it.readText()) }

    fun status(context: Context): String {
        val lock = lock(context)
        val credentialStates = CliSubscriptionCredentialVault(context).publicStates()
        return json.encodeToString(
            kotlinx.serialization.json.JsonObject
                .serializer(),
            buildJsonObject {
                put("protocolVersion", CliRuntimeProtocol.VERSION)
                put("runtimeVersion", BuildConfig.VERSION_NAME)
                put("abi", lock.abi)
                put("lockSha256", CliRuntimeLockCodec.sha256(lock))
                put("bundledArtifactCount", lock.artifacts.count { it.bundled })
                put("credentialState", "AVAILABLE_THIRD_PARTY_ADAPTER")
                put("codexLoginState", credentialStates.getValue("codex"))
                put("claudeLoginState", credentialStates.getValue("claude"))
                put("agentBackendState", "NOT_REGISTERED")
            },
        )
    }
}
