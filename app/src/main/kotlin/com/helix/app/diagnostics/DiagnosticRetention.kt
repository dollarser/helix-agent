package com.helix.app.diagnostics

internal const val DIAGNOSTIC_MIB = 1024L * 1024
internal const val DIAGNOSTIC_DAY = 86_400_000L

data class DiagnosticRetention(
    val normalDays: Int = 14,
    val failureDays: Int = 90,
    val budgetMiB: Int = 256,
) {
    init {
        require(normalDays in 1..365 && failureDays in normalDays..365)
        require(budgetMiB in 64..2048)
    }

    val budgetBytes: Long get() = budgetMiB * DIAGNOSTIC_MIB

    fun ttl(kind: DiagnosticKind): Long =
        when (kind) {
            DiagnosticKind.NORMAL -> normalDays.toLong()
            DiagnosticKind.FAILURE -> failureDays.toLong()
            DiagnosticKind.LOG, DiagnosticKind.ATTACHMENT -> 7L
            DiagnosticKind.FAILURE_ATTACHMENT -> 30L
            DiagnosticKind.TEMPORARY -> 1L
        } * DIAGNOSTIC_DAY
}

enum class DiagnosticKind {
    NORMAL,
    FAILURE,
    LOG,
    ATTACHMENT,
    FAILURE_ATTACHMENT,
    TEMPORARY,
}

internal data class DiagnosticRecord(
    val id: String,
    val owner: String?,
    val kind: DiagnosticKind,
    val event: String,
    val createdAt: Long,
    val body: String?,
    val clearedAt: Long? = null,
)

internal data class DiagnosticArchiveConfig(
    val formatVersion: Int = 1,
    val policy: DiagnosticRetention = DiagnosticRetention(),
    val pinned: Set<String> = emptySet(),
    val lastSweep: Long = 0,
    val dropped: Long = 0,
) {
    init {
        require(formatVersion == 1 && pinned.size <= 10_000 && lastSweep >= 0 && dropped >= 0)
        require(pinned.all { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) })
    }
}

data class DiagnosticArchiveRow(
    val id: String,
    val owner: String?,
    val kind: DiagnosticKind,
    val event: String,
    val createdAt: Long,
    val bytes: Long,
    val pinned: Boolean,
    val cleared: Boolean,
)

data class DiagnosticArchiveStatus(
    val policy: DiagnosticRetention,
    val bytes: Long,
    val byKind: Map<DiagnosticKind, Long>,
    val protectedBytes: Long,
    val dropped: Long,
    val unreadableFiles: Int,
    val rows: List<DiagnosticArchiveRow>,
)
