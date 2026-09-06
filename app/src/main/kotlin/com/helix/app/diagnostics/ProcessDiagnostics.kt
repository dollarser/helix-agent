package com.helix.app.diagnostics

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.security.MessageDigest

/** Bounded, content-free process evidence used by the user-previewed diagnostic bundle. */
data class ProcessEvidence(
    val state: String,
    val heartbeatAtMillis: Long,
    val lastTurnId: String?,
    val lastCorrelationId: String?,
    val lastTurnState: String?,
    val crashType: String?,
    val crashFingerprint: String?,
)

data class ExitEvidence(
    val reason: Int,
    val status: Int,
    val timestampMillis: Long,
    val importance: Int,
)

/**
 * Persists only an allowlisted lifecycle state, timestamp, exception class and one-way stack
 * fingerprint. Prompt, Tool arguments/results, notification/file content and exception messages
 * never enter this store.
 */
class ProcessEvidenceStore internal constructor(
    private val application: Application,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val preferences = application.getSharedPreferences(PREFS, Application.MODE_PRIVATE)

    fun heartbeat(state: String) {
        require(state in ALLOWED_STATES) { "diagnostic state is not allowlisted" }
        preferences
            .edit()
            .putString(KEY_STATE, state)
            .putLong(KEY_HEARTBEAT, nowMillis())
            .apply()
    }

    fun recordCrash(throwable: Throwable) {
        val type = throwable.javaClass.name.take(MAX_TYPE_LENGTH)
        val canonical = throwable.stackTrace.take(MAX_STACK_FRAMES).joinToString("\n") { it.toString() }
        preferences
            .edit()
            .putString(KEY_CRASH_TYPE, type)
            .putString(KEY_CRASH_FINGERPRINT, sha256(canonical))
            .apply()
    }

    /** Records opaque identifiers and an enum name only; no prompt or Tool payload is accepted. */
    fun checkpointTurn(
        turnId: String?,
        correlationId: String?,
        state: String?,
    ) {
        require(turnId == null || isBoundedOpaqueId(turnId)) { "turnId is not a bounded opaque identifier" }
        require(correlationId == null || isBoundedOpaqueId(correlationId)) {
            "correlationId is not a bounded opaque identifier"
        }
        require(state == null || state in ALLOWED_TURN_STATES) { "turn state is not allowlisted" }
        preferences
            .edit()
            .putString(KEY_LAST_TURN_ID, turnId)
            .putString(KEY_LAST_CORRELATION_ID, correlationId)
            .putString(KEY_LAST_TURN_STATE, state)
            .putLong(KEY_HEARTBEAT, nowMillis())
            .apply()
    }

    fun read(): ProcessEvidence =
        ProcessEvidence(
            state = preferences.getString(KEY_STATE, STATE_UNKNOWN) ?: STATE_UNKNOWN,
            heartbeatAtMillis = preferences.getLong(KEY_HEARTBEAT, 0L),
            lastTurnId = preferences.getString(KEY_LAST_TURN_ID, null),
            lastCorrelationId = preferences.getString(KEY_LAST_CORRELATION_ID, null),
            lastTurnState = preferences.getString(KEY_LAST_TURN_STATE, null),
            crashType = preferences.getString(KEY_CRASH_TYPE, null),
            crashFingerprint = preferences.getString(KEY_CRASH_FINGERPRINT, null),
        )

    fun recentExits(limit: Int = MAX_EXIT_RECORDS): List<ExitEvidence> {
        require(limit in 1..MAX_EXIT_RECORDS) { "exit record limit must be 1..$MAX_EXIT_RECORDS" }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val manager = application.getSystemService(ActivityManager::class.java)
        return manager
            .getHistoricalProcessExitReasons(application.packageName, 0, limit)
            .take(limit)
            .map { info ->
                ExitEvidence(
                    reason = info.reason,
                    status = info.status,
                    timestampMillis = info.timestamp,
                    importance = info.importance,
                )
            }
    }

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun isBoundedOpaqueId(value: String): Boolean =
        value.length in 1..MAX_ID_LENGTH && ID_PATTERN.matches(value)

    companion object {
        const val STATE_STARTING = "STARTING"
        const val STATE_ACTIVE = "ACTIVE"
        const val STATE_BACKGROUND = "BACKGROUND"
        const val STATE_UNKNOWN = "UNKNOWN"
        const val HEARTBEAT_INTERVAL_MS = 60_000L
        const val MAX_EXIT_RECORDS = 8
        private const val MAX_TYPE_LENGTH = 160
        private const val MAX_STACK_FRAMES = 32
        private const val MAX_ID_LENGTH = 128
        private const val PREFS = "process-diagnostics-v1"
        private const val KEY_STATE = "state"
        private const val KEY_HEARTBEAT = "heartbeat"
        private const val KEY_CRASH_TYPE = "crash-type"
        private const val KEY_CRASH_FINGERPRINT = "crash-fingerprint"
        private const val KEY_LAST_TURN_ID = "last-turn-id"
        private const val KEY_LAST_CORRELATION_ID = "last-correlation-id"
        private const val KEY_LAST_TURN_STATE = "last-turn-state"
        private val ALLOWED_STATES = setOf(STATE_STARTING, STATE_ACTIVE, STATE_BACKGROUND, STATE_UNKNOWN)
        private val ALLOWED_TURN_STATES =
            com.helix.core.model.TurnState.entries
                .mapTo(mutableSetOf()) { it.name }
        private val ID_PATTERN = Regex("[A-Za-z0-9._:-]+")
    }
}

/** Process-lifetime installer. The uncaught handler records evidence and always delegates. */
class ProcessDiagnostics private constructor(
    private val store: ProcessEvidenceStore,
    private val handler: Handler,
) {
    private val heartbeat =
        object : Runnable {
            override fun run() {
                store.heartbeat(ProcessEvidenceStore.STATE_ACTIVE)
                handler.postDelayed(this, ProcessEvidenceStore.HEARTBEAT_INTERVAL_MS)
            }
        }

    fun start() {
        store.heartbeat(ProcessEvidenceStore.STATE_STARTING)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            store.recordCrash(throwable)
            previous?.uncaughtException(thread, throwable)
        }
        handler.post(heartbeat)
    }

    companion object {
        fun install(application: Application): ProcessDiagnostics =
            ProcessDiagnostics(ProcessEvidenceStore(application), Handler(Looper.getMainLooper())).also { it.start() }
    }
}
