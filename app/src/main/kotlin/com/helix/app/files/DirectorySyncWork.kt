package com.helix.app.files

import android.content.Context
import android.util.AtomicFile
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.helix.app.HelixApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

// KTX edit returns Unit; this durable authorization marker must report commit failure.
@android.annotation.SuppressLint("UseKtx")
internal fun directorySyncService(
    context: Context,
    manual: ManualFileOperations,
): DirectorySyncService {
    val authorization = context.getSharedPreferences("directory-sync-authorization", Context.MODE_PRIVATE)
    val file = AtomicFile(File(context.noBackupFilesDir, "directory-sync/rules.json"))
    return DirectorySyncService(
        manual,
        load = {
            if (!file.baseFile.exists()) {
                null
            } else {
                file.openRead().use { input ->
                    val raw = ImportFileCapture.read(input, 8L * 1024 * 1024).toString(Charsets.UTF_8)
                    if (authorization.getBoolean("bound", false)) {
                        raw
                    } else {
                        DirectorySyncCodec.encode(
                            DirectorySyncCodec
                                .decode(
                                    raw,
                                ).map { it.copy(enabled = false, status = "REVIEW", base = emptyMap()) },
                        )
                    }
                }
            }
        },
        save = { json ->
            check(file.baseFile.parentFile!!.mkdirs() || file.baseFile.parentFile!!.isDirectory)
            val output = file.startWrite()
            try {
                output.write(json.toByteArray())
                file.finishWrite(output)
                check(authorization.edit().putBoolean("bound", true).commit()) { "SYNC_AUTHORIZATION_PERSISTENCE" }
            } catch (failure: java.io.IOException) {
                file.failWrite(output)
                throw failure
            }
        },
        schedule = { id, enabled ->
            val manager = WorkManager.getInstance(context)
            val name = "directory-sync-$id"
            if (enabled) {
                manager.enqueueUniquePeriodicWork(
                    name,
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<DirectorySyncWorker>(15, TimeUnit.MINUTES)
                        .setInitialDelay(15, TimeUnit.MINUTES)
                        .setInputData(workDataOf("rule" to id))
                        .build(),
                )
            } else {
                manager.cancelUniqueWork(name)
            }
        },
    )
}

/** User-created, explicit file rule only. No Agent/model work, process restore or implicit tree grant. */
class DirectorySyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val rule = inputData.getString("rule")
        val container = (applicationContext as HelixApplication).awaitAppContainer()
        val service = container.fileManager.directorySync?.value
        if (rule == null || service == null) return Result.failure()
        val job = currentCoroutineContext()
        withContext(Dispatchers.IO) { service.run(rule, { !job.isActive || isStopped }, automatic = true) }
        val status = service.list().singleOrNull { it.id == rule }?.status
        return if (status == "COMPLETE" || status == "PAUSED") Result.success() else Result.failure()
    }
}
