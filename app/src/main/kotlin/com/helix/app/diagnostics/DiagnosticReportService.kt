package com.helix.app.diagnostics

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** User-triggered, content-free support snapshot. No network, clipboard or conversation access. */
class DiagnosticReportService(
    application: Application,
) {
    private val store = ProcessEvidenceStore(application)
    private val version = application.packageManager.getPackageInfo(application.packageName, 0).versionName ?: "unknown"
    private val applicationId = application.packageName

    suspend fun preview(): String =
        withContext(Dispatchers.IO) {
            buildJsonObject {
                put("format", "helix.process-diagnostics")
                put("appVersion", version)
                put("applicationId", applicationId)
                put("evidence", Json.parseToJsonElement(DiagnosticBundlePreview.collect(store).encode()))
            }.toString().also {
                check(it.toByteArray(Charsets.UTF_8).size <= DiagnosticBundlePreview.MAX_ENCODED_BYTES)
            }
        }
}
