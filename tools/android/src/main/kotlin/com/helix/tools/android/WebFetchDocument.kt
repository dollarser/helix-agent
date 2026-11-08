package com.helix.tools.android

import com.helix.core.model.CancelSignal
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.security.MessageDigest

internal data class WebFetchDocument(
    val title: String,
    val text: String,
    val extraction: String,
    val truncated: Boolean,
    val decodingLoss: Boolean,
    val contentHash: String,
)

/** Parses only bytes already admitted by the transport. Never loads scripts or subresources. */
internal object WebFetchDocuments {
    fun extract(
        page: WebFetchPage,
        deadline: Long,
        cancel: CancelSignal,
    ): WebFetchDocument {
        checkWebFetchActive(deadline, cancel)
        val html = page.mediaType in setOf("text/html", "application/xhtml+xml")
        val charset =
            page.charset?.let {
                try {
                    Charset.forName(it)
                } catch (_: IllegalArgumentException) {
                    throw WebFetchFailure("unsupported-charset")
                }
            }
        val extracted =
            if (html) {
                html(page, charset, deadline, cancel)
            } else {
                Text("", page.bytes.toString(charset ?: Charsets.UTF_8), "text")
            }
        checkWebFetchActive(deadline, cancel)
        val normalized = extracted.value.trim()
        val text = unicodePrefix(normalized, WEB_TEXT_LIMIT)
        return WebFetchDocument(
            unicodePrefix(extracted.title, WEB_TITLE_LIMIT),
            text,
            extracted.mode,
            page.truncated || normalized.length > WEB_TEXT_LIMIT,
            '\ufffd' in extracted.value,
            MessageDigest
                .getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) },
        )
    }

    private fun html(
        page: WebFetchPage,
        charset: Charset?,
        deadline: Long,
        cancel: CancelSignal,
    ): Text {
        val document = ByteArrayInputStream(page.bytes).use { Jsoup.parse(it, charset?.name(), page.url) }
        checkWebFetchActive(deadline, cancel)
        // Bound the whole parsed tree before selectors/text scans, including removed subtrees.
        var nodes = 0
        NodeTraversor.traverse(
            object : NodeVisitor {
                override fun head(
                    node: Node,
                    depth: Int,
                ) {
                    checkWebFetchActive(deadline, cancel)
                    nodes++
                    if (nodes > MAX_DOCUMENT_NODES) throw WebFetchFailure("html-node-limit")
                }

                override fun tail(
                    node: Node,
                    depth: Int,
                ) = Unit
            },
            document,
        )
        val title = document.title()
        document
            .select(
                "script,style,head,nav,footer,form,iframe,object,embed,svg,[hidden],[aria-hidden=true]",
            ).remove()
        // Prefer the page's declared main region. Do not repeatedly score nested subtrees.
        val main =
            (document.selectFirst("main,[role=main]") ?: document.selectFirst("article"))
                ?.takeIf { it.hasText() }
        val root = main ?: document.body()
        val output = StringBuilder()
        NodeTraversor.traverse(
            object : NodeVisitor {
                override fun head(
                    node: Node,
                    depth: Int,
                ) {
                    checkWebFetchActive(deadline, cancel)
                    if (node is Element && (node.isBlock || node.normalName() == "br")) newline(output)
                    if (node is TextNode) output.append(node.wholeText)
                }

                override fun tail(
                    node: Node,
                    depth: Int,
                ) {
                    if (node is Element && node.isBlock) newline(output)
                }
            },
            root,
        )
        return Text(title, output.toString(), if (main == null) "body-text" else "main-text")
    }

    private fun newline(output: StringBuilder) {
        if (output.isNotEmpty() && output.last() != '\n') output.append('\n')
    }

    private data class Text(
        val title: String,
        val value: String,
        val mode: String,
    )

    private const val MAX_DOCUMENT_NODES = 50_000
}

internal fun unicodePrefix(
    value: String,
    limit: Int,
): String {
    var end = minOf(value.length, limit.coerceAtLeast(0))
    if (end in 1 until value.length) {
        if (value[end - 1].isHighSurrogate() && value[end].isLowSurrogate()) end--
    }
    return value.substring(0, end)
}

internal const val WEB_TEXT_LIMIT = 60_000
internal const val WEB_TITLE_LIMIT = 512
