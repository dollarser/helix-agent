package com.helix.feature.browser

/**
 * The lifecycle of one queued download (HXA-060; doc 09 §3.4: 下载限制协议/大小/类型，
 * 绝不自动执行或安装). The queue is user-visible: nothing is ever saved, executed or
 * installed without an explicit user step.
 */
enum class DownloadStatus {
    /** Policy admitted the download; the user must pick a destination before any bytes move. */
    PENDING_CHOICE,

    /** Streaming into the user-picked SAF document. */
    SAVING,

    /** Completed at the picked destination. */
    SAVED,

    /** The transfer failed (network / HTTP / cap / destination). */
    FAILED,

    /** Denied by [BrowserDownloadPolicy] — no bytes were ever fetched. */
    DENIED,
}

/** One row of the browser's download queue. [declaredBytes] is -1 when undeclared. */
data class DownloadItem(
    val id: String,
    val url: String,
    val fileName: String,
    val declaredBytes: Long,
    val status: DownloadStatus,
    /** Set exactly when [status] is [DownloadStatus.DENIED]. */
    val denial: DownloadDenial? = null,
    /** One-line failure detail for the error states (never the raw stack trace). */
    val detail: String? = null,
)
