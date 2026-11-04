package com.helix.app.files

/** Fixed byte pages assign a crossing UTF-8 scalar to the preceding page, exactly once. */
internal fun decodeViewerTextPage(
    bytes: ByteArray,
    pageBytes: Int,
): String {
    require(pageBytes > 0 && bytes.size <= pageBytes + 3)
    var start = 0
    while (start < minOf(3, bytes.size) && bytes[start].toInt() and 0xc0 == 0x80) start++
    var end = minOf(pageBytes, bytes.size)
    while (end < bytes.size && bytes[end].toInt() and 0xc0 == 0x80) end++
    return String(bytes, start, end - start, Charsets.UTF_8)
}

internal fun viewerPageCount(
    size: Long,
    pageBytes: Long,
): Long {
    require(size >= 0 && pageBytes > 0)
    return if (size == 0L) 1 else (size - 1) / pageBytes + 1
}

internal class ViewerSourceChanged : java.io.IOException("VIEWER_SOURCE_CHANGED")
