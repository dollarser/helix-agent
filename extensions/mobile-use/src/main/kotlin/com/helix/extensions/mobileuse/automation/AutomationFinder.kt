package com.helix.extensions.mobileuse.automation

object AutomationFinder {
    fun find(
        snapshot: AutomationSnapshot,
        query: AutomationFindQuery,
    ): AutomationFindResult {
        val matches =
            findAll(snapshot, query)?.take(query.maxResults)
                ?: return AutomationFindResult(AutomationFindStatus.INVALID_QUERY)
        return AutomationFindResult(
            status =
                if (matches.isEmpty()) {
                    AutomationFindStatus.NOT_FOUND
                } else {
                    AutomationFindStatus.FOUND
                },
            nodes = matches,
        )
    }

    /** Internal bounded snapshot matching; output limits must not hide changes from condition waits. */
    fun findAll(
        snapshot: AutomationSnapshot,
        query: AutomationFindQuery,
    ): List<AutomationSnapshotNode>? = if (query.isValid()) snapshot.nodes.filter { it.matches(query) } else null

    private fun AutomationFindQuery.isValid(): Boolean =
        maxResults in 1..MAX_FIND_RESULTS &&
            listOf(text, contentDescription, viewId, className).all { it == null || it.isNotBlank() } &&
            (
                text != null ||
                    contentDescription != null ||
                    viewId != null ||
                    className != null ||
                    clickable != null ||
                    checkable != null ||
                    checked != null
            )

    private fun AutomationSnapshotNode.matches(query: AutomationFindQuery): Boolean =
        text.matches(query.text, query.match) &&
            contentDescription.matches(query.contentDescription, query.match) &&
            viewId.matches(query.viewId, query.match) &&
            className.matches(query.className, query.match) &&
            (query.clickable == null || clickable == query.clickable) &&
            (query.checkable == null || checkable == query.checkable) &&
            (query.checked == null || checked == query.checked)

    @Suppress("ReturnCount")
    private fun String?.matches(
        expected: String?,
        match: AutomationTextMatch,
    ): Boolean {
        if (expected == null) return true
        val actual = this ?: return false
        return when (match) {
            AutomationTextMatch.EXACT -> actual == expected
            AutomationTextMatch.CONTAINS -> actual.contains(expected)
        }
    }

    private const val MAX_FIND_RESULTS = 50
}
