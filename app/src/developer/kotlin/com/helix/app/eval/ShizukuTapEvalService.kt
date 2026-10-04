package com.helix.app.eval

import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.IBinder
import com.helix.app.automation.shizuku.ShizukuUiBridge
import com.helix.app.automation.shizuku.ShizukuUiSelector
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ShizukuTapEvalService : Service() {
    private val running = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val runId = intent?.getStringExtra("runId").orEmpty()
        if (!debuggable || intent?.getBooleanExtra("enabled", false) != true ||
            !runId.matches(Regex("[a-zA-Z0-9_-]{1,64}"))
        ) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (running.compareAndSet(false, true)) {
            Thread(
                {
                    try {
                        evaluate(intent, runId)
                    } finally {
                        running.set(false)
                        stopSelf(startId)
                    }
                },
                "shizuku-tap-eval",
            ).start()
        }
        return START_NOT_STICKY
    }

    private fun evaluate(
        intent: Intent,
        runId: String,
    ) {
        val evidence = File(filesDir, "shizuku-eval-$runId.json")
        evidence.writeText(
            buildJsonObject {
                put("status", "RUNNING")
                put("runId", runId)
            }.toString(),
        )
        runCatching {
            val selector =
                ShizukuUiSelector(
                    requireNotNull(intent.getStringExtra("packageName")),
                    requireNotNull(intent.getStringExtra("resourceId")),
                    requireNotNull(intent.getStringExtra("text")),
                )
            val result = ShizukuUiBridge(applicationContext).clickMatch(selector, { _, _, _ -> true }, { true })
            evidence.writeText(
                buildJsonObject {
                    put("status", "DIAGNOSTIC_ONLY")
                    put("runId", runId)
                    put("completedAtEpochMillis", System.currentTimeMillis())
                    put("serverUid", result.serverUid)
                    put("userServiceUid", result.userServiceUid)
                    put("result", result.result)
                }.toString(),
            )
        }.onFailure { error ->
            evidence.writeText(
                buildJsonObject {
                    put("status", "ERROR_VERIFY_BEFORE_RETRY")
                    put("runId", runId)
                    put("completedAtEpochMillis", System.currentTimeMillis())
                    put("error", error.javaClass.simpleName + ": " + error.message.orEmpty())
                }.toString(),
            )
        }
    }
}
