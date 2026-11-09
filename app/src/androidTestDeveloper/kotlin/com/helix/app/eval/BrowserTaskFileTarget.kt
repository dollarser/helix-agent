package com.helix.app.eval

/** Live DocumentsUI identities retain actual tree paths, not geometric containment alone. */
internal data class BrowserFileNode(
    val text: String,
    val viewId: String,
    val path: List<Int>,
    val clickable: Boolean,
)

internal object BrowserTaskFileTarget {
    private const val ROW = "com.google.android.documentsui:id/item_root"
    private const val TITLE = "android:id/title"
    private const val BREADCRUMB = "com.google.android.documentsui:id/breadcrumb_text"

    fun allowed(
        selected: BrowserFileNode,
        nodes: List<BrowserFileNode>,
    ): Boolean {
        val row = selected.takeIf { it in nodes }?.let { selectedRow(it, nodes) } ?: return false
        val titles = nodes.filter { it.viewId == TITLE && descendant(it.path, row.path) }
        val taskTitles = nodes.filter { it.viewId == TITLE && it.text == "task.html" }
        val downloads = nodes.any { it.viewId == BREADCRUMB && it.text in setOf("Downloads", "Download") }
        return downloads && titles.singleOrNull()?.text == "task.html" && taskTitles.size == 1
    }

    private fun selectedRow(
        selected: BrowserFileNode,
        nodes: List<BrowserFileNode>,
    ): BrowserFileNode? =
        when {
            selected.viewId == ROW && selected.clickable -> {
                selected
            }

            selected.viewId == TITLE && selected.text == "task.html" -> {
                nodes.singleOrNull {
                    it.viewId == ROW && it.clickable && descendant(selected.path, it.path)
                }
            }

            else -> {
                null
            }
        }

    private fun descendant(
        path: List<Int>,
        ancestor: List<Int>,
    ): Boolean = path.size > ancestor.size && path.take(ancestor.size) == ancestor
}
