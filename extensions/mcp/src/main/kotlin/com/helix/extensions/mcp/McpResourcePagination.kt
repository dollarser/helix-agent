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
    val token = resourceContinuation(continuation, sourceBinding, templates)
    val target = token?.page ?: 0
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
        requireResourcePage(scannedBytes <= 1048576, ResourceListingFailure.LIMIT_EXCEEDED)
        hash = hashFields(listOf(hash, page.entries.toString()))
        if (index == target) {
            val next =
                page.nextCursor?.let {
                    requireResourcePage(it !in cursors, ResourceListingFailure.CURSOR_CYCLE)
                    requireResourcePage(target < 15, ResourceListingFailure.LIMIT_EXCEEDED)
                    "1:${target + 1}:$hash:$sourceHash"
                }
            return page.copy(nextCursor = next)
        }
        if (index == target - 1) {
            requireResourcePage(hash == token?.precedingHash, ResourceListingFailure.LISTING_CHANGED)
        }
        cursor = page.nextCursor ?: throw ResourceListingException(ResourceListingFailure.LISTING_CHANGED)
        requireResourcePage(cursors.add(cursor), ResourceListingFailure.CURSOR_CYCLE)
    }
    error("Unreachable resource page")
}

/** One source identity for both host-token admission and production page generation. */
internal fun resourceSourceBinding(config: McpServerConfig): String = config.id.value + "\n" + config.endpoint.full

internal data class ResourceContinuation(
    val page: Int,
    val precedingHash: String,
)

internal enum class ResourceCursorFailure(
    val guidance: String,
) {
    INVALID_CONTINUATION(
        "Invalid resource continuation. Omit cursor to restart listing; use the returned nextCursor unchanged.",
    ),
    SOURCE_CHANGED("Resource source or list type changed. Omit cursor to restart listing on the current source."),
}

/** Safe host-generated error text only; never exposes supplied tokens or remote exception messages. */
internal class ResourceCursorException(
    val reason: ResourceCursorFailure,
) : IllegalArgumentException(reason.guidance)

/** Only the model-facing host token accepts empty as first page; remote cursors remain opaque. */
internal fun resourceContinuation(
    continuation: String?,
    sourceBinding: String,
    templates: Boolean,
): ResourceContinuation? {
    if (continuation == null || continuation.isEmpty()) return null
    val token = continuation.split(':')
    val page = token.getOrNull(1)?.toIntOrNull()
    val hash = Regex("[a-f0-9]{64}")
    val syntaxValid = token.size == 4 && token[0] == "1"
    requireHostContinuation(syntaxValid && page in 1..15 && token[1] == page.toString())
    requireHostContinuation(hash.matches(token[2]) && hash.matches(token[3]))
    requireHostContinuation(
        token[3] == hashFields(listOf(sourceBinding, templates.toString())),
        ResourceCursorFailure.SOURCE_CHANGED,
    )
    return ResourceContinuation(requireNotNull(page), token[2])
}

private fun requireHostContinuation(
    valid: Boolean,
    reason: ResourceCursorFailure = ResourceCursorFailure.INVALID_CONTINUATION,
) {
    if (!valid) throw ResourceCursorException(reason)
}
