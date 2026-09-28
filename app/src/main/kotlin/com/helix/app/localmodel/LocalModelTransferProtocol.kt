package com.helix.app.localmodel

/** A resumed body is appendable only when its range exactly matches the verified asset request. */
internal object LocalModelTransferProtocol {
    fun resumes(
        status: Int,
        contentRange: String?,
        offset: Long,
        size: Long,
    ): Boolean {
        require(offset in 0 until size)
        require(status == 200 || status == 206) { "Use a direct HTTPS model download URL" }
        if (status == 206) require(contentRange == "bytes $offset-${size - 1}/$size")
        return status == 206
    }
}
