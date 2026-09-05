package com.helix.runtime.cli.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.security.MessageDigest

class CliRuntimeLockException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

@Suppress("TooManyFunctions") // Closed-schema readers stay beside the only CLI lock codec.
object CliRuntimeLockCodec {
    const val SUPPORTED_VERSION = 1
    private val json = Json { isLenient = false }
    private val rootKeys = setOf("lockVersion", "abi", "artifacts")
    private val artifactKeys =
        setOf("id", "version", "kind", "bundled", "url", "size", "sha256", "license", "licenseUrl", "termsUrl")
    private val sha256Pattern = Regex("[0-9a-f]{64}")
    private val idPattern = Regex("[a-z0-9][a-z0-9.-]{0,63}")

    fun parse(text: String): CliRuntimeLock {
        val root =
            try {
                json.parseToJsonElement(text).jsonObject
            } catch (e: IllegalArgumentException) {
                throw CliRuntimeLockException("cli-runtime-lock must be one JSON object", e)
            }
        requireKeys(root, rootKeys, "cli-runtime-lock")
        val version = root.requiredInt("lockVersion", "cli-runtime-lock")
        if (version != SUPPORTED_VERSION) fail("unsupported lockVersion: $version")
        val abi = root.requiredString("abi", "cli-runtime-lock")
        if (abi != "arm64-v8a") fail("unsupported abi: $abi")
        val array =
            try {
                root.getValue("artifacts").jsonArray
            } catch (e: IllegalArgumentException) {
                throw CliRuntimeLockException("cli-runtime-lock.artifacts must be an array", e)
            }
        if (array.isEmpty()) fail("cli-runtime-lock.artifacts must not be empty")
        val artifacts = array.mapIndexed { index, element -> parseArtifact(element.jsonObject, index) }
        if (artifacts.map { it.id }.toSet().size != artifacts.size) fail("duplicate artifact id")
        val required = setOf("node", "codex-app-server", "claude-code-npm")
        if (!artifacts.map { it.id }.containsAll(required)) fail("required official CLI metadata is missing")
        if (artifacts.any { it.bundled }) fail("HXA-110 metadata lock must not bundle executables before HXA-111/112")
        return CliRuntimeLock(version, abi, artifacts)
    }

    fun canonical(lock: CliRuntimeLock): String =
        buildString {
            append("{\"lockVersion\":${lock.lockVersion},\"abi\":\"")
            append(escape(lock.abi))
            append("\",\"artifacts\":[")
            lock.artifacts.forEachIndexed { index, item ->
                if (index > 0) append(',')
                append("{\"id\":\"").append(escape(item.id))
                append("\",\"version\":\"").append(escape(item.version))
                append("\",\"kind\":\"").append(escape(item.kind))
                append("\",\"bundled\":").append(item.bundled)
                append(",\"url\":\"").append(escape(item.url))
                append("\",\"size\":").append(item.size)
                append(",\"sha256\":\"").append(item.sha256)
                append("\",\"license\":\"").append(escape(item.license))
                append("\",\"licenseUrl\":\"").append(escape(item.licenseUrl))
                append("\",\"termsUrl\":\"").append(escape(item.termsUrl)).append("\"}")
            }
            append("]}")
        }

    fun sha256(lock: CliRuntimeLock): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(canonical(lock).encodeToByteArray())
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun parseArtifact(
        obj: JsonObject,
        index: Int,
    ): CliArtifact {
        val where = "cli-runtime-lock.artifacts[$index]"
        requireKeys(obj, artifactKeys, where)
        val artifact =
            CliArtifact(
                id = obj.requiredString("id", where),
                version = obj.requiredString("version", where),
                kind = obj.requiredString("kind", where),
                bundled = obj.requiredBoolean("bundled", where),
                url = obj.requiredString("url", where),
                size = obj.requiredLong("size", where),
                sha256 = obj.requiredString("sha256", where),
                license = obj.requiredString("license", where),
                licenseUrl = obj.requiredString("licenseUrl", where),
                termsUrl = obj.requiredString("termsUrl", where),
            )
        if (!idPattern.matches(artifact.id)) fail("$where.id is invalid")
        if (artifact.version.isBlank() || artifact.version.equals("latest", true)) fail("$where.version is not fixed")
        if (artifact.kind !in setOf("runtime", "official-cli")) fail("$where.kind is invalid")
        if (artifact.size !in 1..1_073_741_824L) fail("$where.size is outside bounds")
        if (!sha256Pattern.matches(artifact.sha256)) fail("$where.sha256 is invalid")
        listOf(artifact.url, artifact.licenseUrl, artifact.termsUrl).forEach { requireHttps(it, where) }
        return artifact
    }

    private fun requireKeys(
        obj: JsonObject,
        allowed: Set<String>,
        where: String,
    ) {
        val unknown = obj.keys - allowed
        val missing = allowed - obj.keys
        if (unknown.isNotEmpty()) fail("$where has unknown keys: ${unknown.sorted()}")
        if (missing.isNotEmpty()) fail("$where is missing keys: ${missing.sorted()}")
    }

    private fun JsonObject.requiredString(
        key: String,
        where: String,
    ): String =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() } ?: fail("$where.$key must be a string")

    private fun JsonObject.requiredInt(
        key: String,
        where: String,
    ): Int = this[key]?.jsonPrimitive?.intOrNull ?: fail("$where.$key must be an integer")

    private fun JsonObject.requiredLong(
        key: String,
        where: String,
    ): Long = this[key]?.jsonPrimitive?.longOrNull ?: fail("$where.$key must be an integer")

    private fun JsonObject.requiredBoolean(
        key: String,
        where: String,
    ): Boolean = this[key]?.jsonPrimitive?.booleanOrNull ?: fail("$where.$key must be a boolean")

    private fun requireHttps(
        value: String,
        where: String,
    ) {
        val uri =
            try {
                URI(value)
            } catch (e: IllegalArgumentException) {
                throw CliRuntimeLockException("$where URL is invalid", e)
            }
        val invalidAuthority = uri.host.isNullOrBlank() || uri.userInfo != null
        val invalidLocation = uri.scheme != "https" || uri.fragment != null
        if (invalidAuthority || invalidLocation) {
            fail("$where URL must be absolute HTTPS without credentials or fragment")
        }
    }

    private fun escape(value: String): String =
        buildString {
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000c' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
                }
            }
        }

    private fun fail(message: String): Nothing = throw CliRuntimeLockException(message)
}
