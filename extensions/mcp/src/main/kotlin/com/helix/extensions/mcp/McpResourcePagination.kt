package com.helix.extensions.mcp

/**
 * Server cursors never cross connections. A host continuation replays bounded read-only
 * discovery on the new connection, verifying preceding pages before using its fresh cursor.
 * Changed metadata fails rather than silently skipping/reordering a different snapshot.
 */
internal suspend fun resourcePage(
    session: McpClientSession,
    templates: Boolean,
    continuation: String?,
    sourceBinding: String,
    checkCurrent: () -> Unit,
): McpResourcePage {
    val sourceHash = hashFields(listOf(sourceBinding, templates.toString()))
    val token = continuation?.split(':')
    val target = token?.getOrNull(1)?.toIntOrNull() ?: 0
    require(token == null || (token.size == 4 && token[0] == "1" && target in 1..15 && token[3] == sourceHash)) {
        "Invalid MCP resource continuation or source changed"
    }
    var cursor: String? = null
    var hash = hashFields(listOf(sourceHash))
    val cursors = mutableSetOf<String>()
    var scannedBytes = 0L
    for (index in 0..target) {
        checkCurrent()
        val page = if (templates) session.listResourceTemplates(cursor) else session.listResources(cursor)
        scannedBytes +=
            page.entries.sumOf {
                it
                    .toString()
                    .toByteArray()
                    .size
                    .toLong()
            }
        require(scannedBytes <= 1048576) { "MCP discovery exceeds 1 MiB metadata" }
        hash = hashFields(listOf(hash, page.entries.toString()))
        if (index == target) {
            val next =
                page.nextCursor?.let {
                    require(it !in cursors) { "MCP resource cursor cycle" }
                    require(target < 15) { "MCP resource discovery exceeds 16 pages" }
                    "1:${target + 1}:$hash:$sourceHash"
                }
            return page.copy(nextCursor = next)
        }
        if (index == target - 1) {
            require(hash == token?.get(2)) { "MCP resource listing changed; restart discovery" }
        }
        cursor = requireNotNull(page.nextCursor) { "MCP resource listing changed; restart discovery" }
        require(cursors.add(cursor)) { "MCP resource cursor cycle" }
    }
    error("Unreachable resource page")
}
