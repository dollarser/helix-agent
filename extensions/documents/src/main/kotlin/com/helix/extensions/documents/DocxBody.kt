package com.helix.extensions.documents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.xml.sax.Attributes
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2

private val UNSUPPORTED =
    setOf("object", "oleObject", "chart", "diagram", "altChunk", "oMath", "oMathPara", "pict", "gridSpan", "vMerge")

internal class DocxBody(
    private val start: Int,
    private val collect: Boolean,
    private val relations: Map<String, DocxRelation>,
) : DefaultHandler2() {
    var count = 0
    var lastReturned = start - 1
    var truncated = false
    val blocks = mutableListOf<JsonObject>()
    val imageIds = linkedSetOf<String>()
    val warnings = linkedSetOf<String>()
    private val text = StringBuilder()
    private var textNode = false
    private var tableDepth = 0
    private var table = 0
    private var row = 0
    private var column = 0
    private var heading = ""
    private var totalText = 0
    private var returnedCells = 0
    private var overflow = false
    private var stopped = false
    private val images = linkedSetOf<String>()
    private val cells = mutableListOf<JsonObject>()
    private var cellStart = 0
    var bodySeen = false
    private var inBody = false

    override fun startElement(
        uri: String,
        localName: String,
        qName: String,
        attributes: Attributes,
    ) {
        if (uri == DocxNamespaces.WORD && localName == "body") {
            checkDocument(!bodySeen, "MALFORMED_DOCUMENT")
            bodySeen = true
            inBody = true
        }
        if (!inBody) return
        if (uri == DocxNamespaces.WORD) wordElement(localName, attributes)
        if (localName in UNSUPPORTED) mark("Unsupported $localName at block ${count + 1}")
        if (localName == "blip" && uri == DocxNamespaces.DRAWING) imageElement(attributes)
    }

    private fun wordElement(
        localName: String,
        attributes: Attributes,
    ) {
        when (localName) {
            "p" -> {
                if (tableDepth == 0) reset()
            }

            "pStyle" -> {
                if (tableDepth == 0) heading = (attributes.getValue(DocxNamespaces.WORD, "val") ?: "").take(128)
            }

            "t" -> {
                textNode = true
            }

            "tab" -> {
                append("\t")
            }

            "br" -> {
                append("\n")
            }

            else -> {
                tableElement(localName)
            }
        }
    }

    private fun tableElement(localName: String) {
        when (localName) {
            "tbl" -> {
                tableDepth++
                if (tableDepth == 1) {
                    table++
                    row = 0
                }
                if (tableDepth > 1) mark("Nested table flattened within row")
            }

            "tr" -> {
                if (tableDepth == 1) {
                    reset()
                    row++
                    column = 0
                }
            }

            "tc" -> {
                if (tableDepth == 1) {
                    column++
                    cellStart = text.length
                }
            }
        }
    }

    private fun imageElement(attributes: Attributes) {
        val id =
            attributes.getValue(DocxNamespaces.RELATIONSHIP, "embed")
                ?: attributes.getValue(DocxNamespaces.RELATIONSHIP, "link")
        if (id != null) {
            if (id !in relations) throw SAXException("Missing image relationship")
            imageIds += id
            if (imageIds.size > 2048) throw DocumentException("RESOURCE_LIMIT")
            if (images.size < 16) images += id else overflow = true
            if (relations[id]!!.external) mark("External image at block ${count + 1}; not fetched")
        }
    }

    override fun characters(
        ch: CharArray,
        start: Int,
        length: Int,
    ) {
        if (textNode) append(String(ch, start, length))
    }

    private fun append(value: String) {
        val available = DocumentLimits.TEXT - text.length
        text.append(value.take(available))
        if (value.length > available) overflow = true
    }

    private fun mark(warning: String) {
        if (warnings.size < 64) warnings += warning
    }

    private fun reset() {
        text.setLength(0)
        images.clear()
        cells.clear()
        heading = ""
        overflow = false
    }

    override fun endElement(
        uri: String,
        localName: String,
        qName: String,
    ) {
        if (uri != DocxNamespaces.WORD || !inBody) return
        when (localName) {
            "t" -> {
                textNode = false
            }

            "tc" -> {
                if (tableDepth == 1 &&
                    cells.size < 256
                ) {
                    cells +=
                        buildJsonObject {
                            put("column", column)
                            put("text", text.substring(cellStart).dropLastWhile { it.isHighSurrogate() })
                        }
                }
            }

            "p" -> {
                if (tableDepth == 0) finish("paragraph") else append("\n")
            }

            "tr" -> {
                if (tableDepth == 1) finish("tableRow")
            }

            "tbl" -> {
                tableDepth--
            }

            "body" -> {
                inBody = false
            }
        }
    }

    private fun finish(kind: String) {
        count++
        val outsideRange = count < start || blocks.size >= DocumentLimits.BLOCKS
        if (!collect || stopped || outsideRange) return
        if (text.length > DocumentLimits.TEXT - totalText && blocks.isNotEmpty()) {
            stopped = true
            return
        }
        val remaining = DocumentLimits.TEXT - totalText
        val value = text.toString().take(remaining).dropLastWhile { it.isHighSurrogate() }
        val selectedCells = cells.take(256 - returnedCells)
        val omittedCells = kind == "tableRow" && column > selectedCells.size
        val cut = overflow || text.length > remaining || omittedCells
        if (cut) truncated = true
        blocks +=
            buildJsonObject {
                put("location", "block:$count")
                put("kind", kind)
                put("text", value)
                put("truncated", cut)
                if (heading.isNotBlank()) put("style", heading)
                if (kind == "tableRow") {
                    put("table", table)
                    put("row", row)
                    put("cells", JsonArray(selectedCells))
                }
                putJsonArray("images") { images.forEach { add(it) } }
            }
        returnedCells += selectedCells.size
        totalText += value.length
        lastReturned = count
    }
}
