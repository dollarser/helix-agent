package com.helix.app.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.URI

internal const val MODELSCOPE_PROJECT = "https://modelscope.cn/datasets/dollars/Helix"
internal const val UPDATE_MANIFEST_URL = "$MODELSCOPE_PROJECT/resolve/master/update-manifest.json"

internal data class AppRelease(
    val versionName: String,
    val versionCode: Long,
    val packageName: String,
    val abis: List<String>,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
) {
    val version: AppVersion = AppVersion.parse(versionName)
}

/** Manifest metadata is untrusted. It can offer only an APK in the owner's ModelScope repository. */
internal object UpdateManifest {
    fun parse(text: String): List<AppRelease> {
        val root = Json.parseToJsonElement(text).jsonObject
        require(root.getValue("schemaVersion").jsonPrimitive.long == 1L)
        val rows = root.getValue("releases").jsonArray
        require(rows.size in 1..32)
        return rows
            .map { element ->
                val row = element.jsonObject
                AppRelease(
                    versionName = row.getValue("versionName").jsonPrimitive.content,
                    versionCode = row.getValue("versionCode").jsonPrimitive.long,
                    packageName = row.getValue("packageName").jsonPrimitive.content,
                    abis = row.getValue("abis").jsonArray.map { it.jsonPrimitive.content },
                    apkUrl = row.getValue("apkUrl").jsonPrimitive.content,
                    sha256 = row.getValue("sha256").jsonPrimitive.content,
                    sizeBytes = row.getValue("sizeBytes").jsonPrimitive.long,
                ).also(::validate)
            }.also { releases ->
                val identities =
                    releases.flatMap { release ->
                        release.abis.map { Triple(release.packageName, release.versionName, it) }
                    }
                require(identities.distinct().size == identities.size)
            }
    }

    private fun validate(release: AppRelease) {
        require(release.packageName in setOf("com.helix.agent", "com.helix.agent.developer"))
        require(release.versionCode > 0)
        require(release.abis.isNotEmpty() && release.abis.distinct().size == release.abis.size)
        require(release.abis.all { it in setOf("arm64-v8a", "x86_64") })
        require(release.sizeBytes in 1..2_000_000_000L)
        require(release.sha256.matches(Regex("[a-f0-9]{64}")))
        require(isDownloadUrl(release.apkUrl))
    }

    fun isDownloadUrl(value: String): Boolean =
        runCatching {
            val url = URI(value)
            url.scheme == "https" && url.host == "modelscope.cn" && url.port == -1 &&
                url.rawUserInfo == null && url.rawQuery == null && url.rawFragment == null &&
                url.rawPath.matches(
                    Regex("/datasets/dollars/Helix/resolve/[A-Za-z0-9._-]+/(?:[A-Za-z0-9_.-]+/)*[A-Za-z0-9_.-]+\\.apk"),
                ) &&
                url.rawPath.split('/').none { it == "." || it == ".." }
        }.getOrDefault(false)

    fun newestCompatible(
        releases: List<AppRelease>,
        packageName: String,
        supportedAbis: List<String>,
    ): AppRelease? =
        releases
            .filter { it.packageName == packageName && it.abis.any(supportedAbis::contains) }
            .sortedWith(
                compareByDescending<AppRelease> { it.version }
                    .thenBy { release -> supportedAbis.indexOfFirst { it in release.abis } },
            ).firstOrNull()
}
