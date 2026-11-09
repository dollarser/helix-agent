package com.helix.app.eval

internal data class BrowserResolverNode(
    val text: String,
    val viewId: String,
    val packageName: String,
    val path: List<Int>,
    val clickable: Boolean,
    val enabled: Boolean,
)

/** Test-only resolver boundary. Tree ancestry, never rectangle containment, identifies Chrome's row. */
internal object BrowserResolverTarget {
    fun allowed(
        selected: BrowserResolverNode,
        nodes: List<BrowserResolverNode>,
    ): Boolean {
        val pkg = selected.packageName
        if (pkg !in setOf("android", "com.android.intentresolver") || selected !in nodes || !selected.enabled) {
            return false
        }
        val list = nodes.singleOrNull { it.packageName == pkg && it.viewId == "$pkg:id/resolver_list" }
        return list != null &&
            when (selected.viewId) {
                "$pkg:id/button_once" -> selected.clickable && selected.text in setOf("Just once", "JUST ONCE")
                else -> allowedChrome(selected, nodes, list)
            }
    }

    private fun allowedChrome(
        selected: BrowserResolverNode,
        nodes: List<BrowserResolverNode>,
        list: BrowserResolverNode,
    ): Boolean {
        val pkg = selected.packageName
        val chrome =
            nodes.singleOrNull {
                it.packageName == pkg && it.viewId == "$pkg:id/text1" && it.text == "Chrome"
            }
        return chrome?.let { label ->
            val row = nodes.singleOrNull { isChromeRow(it, list, label) }
            row != null && uniqueChromeSelection(selected, nodes, row, label)
        } ?: false
    }

    private fun isChromeRow(
        candidate: BrowserResolverNode,
        list: BrowserResolverNode,
        chrome: BrowserResolverNode,
    ): Boolean =
        candidate.packageName == chrome.packageName && candidate.enabled && candidate.clickable &&
            candidate.viewId.isEmpty() && candidate.path.size == list.path.size + 1 &&
            descendant(candidate.path, list.path) && descendant(chrome.path, candidate.path)

    private fun uniqueChromeSelection(
        selected: BrowserResolverNode,
        nodes: List<BrowserResolverNode>,
        row: BrowserResolverNode,
        chrome: BrowserResolverNode,
    ): Boolean {
        val pkg = selected.packageName
        val labels = nodes.filter { descendant(it.path, row.path) && it.viewId == "$pkg:id/text1" }
        return labels.singleOrNull() == chrome && chrome.enabled && (selected == row || selected == chrome)
    }

    private fun descendant(
        path: List<Int>,
        ancestor: List<Int>,
    ) = path.size > ancestor.size && path.take(ancestor.size) == ancestor
}
