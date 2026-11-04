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
    private val pluginPerformance: () -> kotlinx.serialization.json.JsonObject = { buildJsonObject {} },
) {
    suspend fun appLogs(): AppLogSnapshot =
        withContext(Dispatchers.IO) {
            AppLogReader.read().also { DiagnosticArchives.record(DiagnosticKind.LOG, "logcat.snapshot", it.text) }
        }

    suspend fun retentionStatus(): DiagnosticArchiveStatus = withContext(Dispatchers.IO) { DiagnosticArchives.status() }

    suspend fun setRetention(policy: DiagnosticRetention) =
        withContext(Dispatchers.IO) {
            DiagnosticArchives.configure(policy)
        }

    suspend fun pinDiagnostic(
        id: String,
        pinned: Boolean,
    ) = withContext(Dispatchers.IO) {
        DiagnosticArchives.pin(id, pinned)
    }

    suspend fun diagnosticDetail(id: String): String? = withContext(Dispatchers.IO) { DiagnosticArchives.detail(id) }

    suspend fun cleanDiagnostics() = withContext(Dispatchers.IO) { DiagnosticArchives.clean() }

    fun diagnosticStorageFailure(): Boolean = DiagnosticArchives.failure != null

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
                put("pluginPerformance", pluginPerformance())
            }.toString().also {
                check(it.toByteArray(Charsets.UTF_8).size <= DiagnosticBundlePreview.MAX_ENCODED_BYTES + 12 * 1024)
                DiagnosticArchives.record(DiagnosticKind.NORMAL, "diagnostic.preview", it)
            }
        }
}
