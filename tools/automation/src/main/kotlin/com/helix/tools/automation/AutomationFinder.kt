package com.helix.tools.automation

object AutomationFinder {
    fun find(
        snapshot: AutomationSnapshot,
        query: AutomationFindQuery,
    ): AutomationFindResult {
        if (!query.isValid()) return AutomationFindResult(AutomationFindStatus.INVALID_QUERY)
        val matches = snapshot.nodes.filter { it.matches(query) }.take(query.maxResults)
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

    private fun AutomationFindQuery.isValid(): Boolean =
        maxResults in 1..MAX_FIND_RESULTS &&
            listOf(text, contentDescription, viewId, className).all { it == null || it.isNotBlank() } &&
            (
                text != null ||
                    contentDescription != null ||
                    viewId != null ||
                    className != null ||
                    clickable != null
            )

    private fun AutomationSnapshotNode.matches(query: AutomationFindQuery): Boolean =
        text.matches(query.text, query.match) &&
            contentDescription.matches(query.contentDescription, query.match) &&
            viewId.matches(query.viewId, query.match) &&
            className.matches(query.className, query.match) &&
            (query.clickable == null || clickable == query.clickable)

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
