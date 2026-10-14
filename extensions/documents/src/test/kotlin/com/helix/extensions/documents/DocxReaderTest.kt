package com.helix.extensions.documents

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocxReaderTest {
    private val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val r = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val a = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private val relNs = "http://schemas.openxmlformats.org/package/2006/relationships"
    private val typeNs = "http://schemas.openxmlformats.org/package/2006/content-types"
    private val mainType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"

    private fun relation(
        id: String,
        target: String,
        kind: String = "image",
        mode: String = "Internal",
    ) = """<Relationship Id="$id" Type="$r/$kind" """ +
        """Target="$target" TargetMode="$mode"/>"""

    private fun pkg(
        body: String,
        extras: Map<String, ByteArray> = emptyMap(),
        rels: String = "",
        types: String? = null,
    ): ByteArray {
        val contentTypes =
            types ?: (
                """<Types xmlns="$typeNs">""" +
                    """<Override PartName="/word/document.xml" ContentType="$mainType"/></Types>"""
            )
        val root =
            """<Relationships xmlns="$relNs">""" +
                relation("main", "word/document.xml", "officeDocument") + "</Relationships>"
        val document =
            """<w:document xmlns:w="$w" xmlns:r="$r" xmlns:a="$a"><w:body>""" +
                body + "</w:body></w:document>"
        val relationships = """<Relationships xmlns="$relNs">$rels</Relationships>"""
        val parts =
            linkedMapOf(
                "[Content_Types].xml" to contentTypes.toByteArray(),
                "_rels/.rels" to root.toByteArray(),
                "word/document.xml" to document.toByteArray(),
                "word/_rels/document.xml.rels" to relationships.toByteArray(),
            )
        parts.putAll(extras)
        return ByteArrayOutputStream()
            .also { out ->
                ZipOutputStream(out).use { zip ->
                    parts.forEach { (name, bytes) ->
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
            }.toByteArray()
    }

    private fun paragraph(value: String) = "<w:p><w:r><w:t>$value</w:t></w:r></w:p>"

    private fun picture(
        id: String,
        linked: Boolean = false,
    ): String {
        val binding = if (linked) "link" else "embed"
        return """<w:p><w:r><w:drawing><a:blip r:$binding="$id"/></w:drawing></w:r></w:p>"""
    }

    private fun read(
        bytes: ByteArray,
        operation: String = "read",
        start: Int = 1,
        imageId: String? = null,
    ) = DocxReader.read(bytes.inputStream(), operation, start, imageId)

    @Test fun orderedHeadingsParagraphsAndTableCellsHaveRealBlockLocations() {
        val title = """<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>标题</w:t></w:r></w:p>"""
        val table = "<w:tbl><w:tr><w:tc>${paragraph("甲")}</w:tc><w:tc>${paragraph("乙")}</w:tc></w:tr></w:tbl>"
        val result = read(pkg(title + paragraph("正文") + table)).metadata
        val blocks = result.getValue("blocks").jsonArray
        assertEquals(3, blocks.size)
        assertEquals("block:1", blocks[0].jsonObject["location"]!!.jsonPrimitive.content)
        assertEquals("Heading1", blocks[0].jsonObject["style"]!!.jsonPrimitive.content)
        assertEquals("tableRow", blocks[2].jsonObject["kind"]!!.jsonPrimitive.content)
        assertEquals(
            "乙\n",
            blocks[2]
                .jsonObject["cells"]!!
                .jsonArray[1]
                .jsonObject["text"]!!
                .jsonPrimitive.content,
        )
        assertFalse(result.containsKey("pageCount"))
    }

    @Test fun inspectReturnsCountsWithoutDocumentProse() {
        val result = read(pkg(paragraph("secret body")), "inspect").metadata
        assertEquals("1", result["blockCount"]!!.jsonPrimitive.content)
        assertFalse(result.toString().contains("secret body"))
        assertFalse(result.containsKey("blocks"))
    }

    @Test fun blockPaginationDoesNotSkipTheFirstUnreturnedBlock() {
        val bytes = pkg((1..35).joinToString("") { paragraph("line$it") })
        val first = read(bytes).metadata
        assertEquals(32, first["blocks"]!!.jsonArray.size)
        assertEquals("33", first["nextCursor"]!!.jsonPrimitive.content)
        val last = read(bytes, start = 33).metadata
        assertEquals(3, last["blocks"]!!.jsonArray.size)
        assertFalse(last.containsKey("nextCursor"))
    }

    @Test fun textBudgetStopsContiguouslyAndLongBlocksDiscloseTruncation() {
        val bytes = pkg(paragraph("a".repeat(15000)) + paragraph("b".repeat(10000)) + paragraph("c"))
        val first = read(bytes).metadata
        assertEquals(1, first["blocks"]!!.jsonArray.size)
        assertEquals("2", first["nextCursor"]!!.jsonPrimitive.content)
        val large = read(pkg(paragraph("x".repeat(21000)))).metadata
        assertEquals("true", large["truncated"]!!.jsonPrimitive.content)
        assertEquals(
            20000,
            large["blocks"]!!
                .jsonArray[0]
                .jsonObject["text"]!!
                .jsonPrimitive.content.length,
        )
    }

    @Test fun largeTablesContinueByRow() {
        val bytes =
            pkg(
                "<w:tbl>" +
                    (1..34).joinToString("") {
                        "<w:tr><w:tc>${paragraph("row$it")}</w:tc></w:tr>"
                    } + "</w:tbl>",
            )
        assertEquals("33", read(bytes).metadata["nextCursor"]!!.jsonPrimitive.content)
        assertEquals(
            "row33\n",
            read(bytes, start = 33)
                .metadata["blocks"]!!
                .jsonArray[0]
                .jsonObject["text"]!!
                .jsonPrimitive.content,
        )
    }

    @Test fun rowLocationsRestartForEachTopLevelTable() {
        val table = "<w:tbl><w:tr><w:tc>${paragraph("cell")}</w:tc></w:tr></w:tbl>"
        val blocks = read(pkg(table + table)).metadata["blocks"]!!.jsonArray
        assertEquals("1", blocks[0].jsonObject["row"]!!.jsonPrimitive.content)
        assertEquals("1", blocks[1].jsonObject["row"]!!.jsonPrimitive.content)
        assertEquals("2", blocks[1].jsonObject["table"]!!.jsonPrimitive.content)
    }

    @Test fun embeddedImagesUseDeclaredRelationshipsAndActualBytes() {
        val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0)
        val bytes = pkg(picture("pic"), mapOf("word/media/a.png" to png), relation("pic", "media/a.png"))
        assertEquals(
            "pic",
            read(bytes)
                .metadata["blocks"]!!
                .jsonArray[0]
                .jsonObject["images"]!!
                .jsonArray[0]
                .jsonPrimitive.content,
        )
        assertArrayEquals(png, read(bytes, "view", imageId = "pic").image)
        assertEquals(
            "RANGE_OUT_OF_BOUNDS",
            assertThrows(DocumentException::class.java) {
                read(bytes, "view", imageId = "unseen")
            }.code,
        )
    }

    @Test fun externalImageIsReportedAndNeverFetched() {
        val bytes =
            pkg(
                picture("remote", linked = true),
                rels = relation("remote", "https://invalid.example/image.png", mode = "External"),
            )
        assertTrue(read(bytes).metadata.toString().contains("not fetched"))
        assertEquals(
            "UNSUPPORTED_OBJECT",
            assertThrows(DocumentException::class.java) {
                read(bytes, "view", imageId = "remote")
            }.code,
        )
    }

    @Test fun unsupportedObjectsAreMarkedWithTheirBlockLocation() {
        val result = read(pkg("<w:p><w:object/></w:p>")).metadata
        assertTrue(result.toString().contains("Unsupported object at block 1"))
        assertEquals("true", result["partial"]!!.jsonPrimitive.content)
    }

    @Test fun extensionSpoofMacroAndMissingMainPartsAreNotDocuments() {
        for (bytes in listOf(
            byteArrayOf(1, 2, 3),
            pkg(paragraph("x"), types = "<Types/>"),
            pkg(
                paragraph("x"),
                mapOf("word/document.xml" to byteArrayOf(1, 2)),
            ),
        )) {
            assertThrows(DocumentException::class.java) { read(bytes) }
        }
        assertThrows(
            DocumentException::class.java,
        ) { read(pkg(paragraph("x"), types = """<Types xmlns="$typeNs"/>""")) }
    }

    @Test fun zipTraversalAndRelationshipTraversalAreRejected() {
        assertThrows(DocumentException::class.java) { read(pkg(paragraph("x"), mapOf("../secret" to byteArrayOf(1)))) }
        assertThrows(
            DocumentException::class.java,
        ) { read(pkg(paragraph("x"), rels = relation("bad", "../../../secret"))) }
    }

    @Test fun duplicateRelationshipIdsAreRejected() {
        val item = relation("same", "media/a.png")
        assertThrows(DocumentException::class.java) { read(pkg(paragraph("x"), rels = item + item)) }
    }

    @Test fun dtdAndExternalEntitiesAreRejectedWithoutReadingFiles() {
        val declaration = """<!DOCTYPE document [<!ENTITY x SYSTEM "file:///should-not-open">]>"""
        val xml = declaration + """<w:document xmlns:w="$w"><w:body>""" + paragraph("&x;") + "</w:body></w:document>"
        assertEquals(
            "MALFORMED_DOCUMENT",
            assertThrows(DocumentException::class.java) {
                read(pkg("", mapOf("word/document.xml" to xml.toByteArray())))
            }.code,
        )
    }

    @Test fun excessiveXmlAndDeepNestingAreRejected() {
        assertEquals(
            "RESOURCE_LIMIT",
            assertThrows(DocumentException::class.java) {
                read(pkg("", mapOf("word/document.xml" to ByteArray(8 * 1024 * 1024 + 1))))
            }.code,
        )
        assertEquals(
            "MALFORMED_DOCUMENT",
            assertThrows(DocumentException::class.java) {
                read(pkg("<w:p>".repeat(65) + "</w:p>".repeat(65)))
            }.code,
        )
    }

    @Test fun unknownBlocksAndMissingImageRelationsFailExplicitly() {
        assertEquals(
            "RANGE_OUT_OF_BOUNDS",
            assertThrows(DocumentException::class.java) {
                read(pkg(paragraph("x")), start = 2)
            }.code,
        )
        assertThrows(DocumentException::class.java) { read(pkg(picture("missing"))) }
    }

    @Test fun emptyBodyIsAValidInspectionAndNamespaceSpoofsAreRejected() {
        assertEquals("0", read(pkg(""), "inspect").metadata["blockCount"]!!.jsonPrimitive.content)
        assertThrows(DocumentException::class.java) {
            read(
                pkg(
                    "",
                    mapOf("word/document.xml" to "<document><body/></document>".toByteArray()),
                ),
            )
        }
    }

    @Test fun duplicateMembersAndTooManyMembersAreRejected() {
        val bytes =
            pkg(paragraph("x"), mapOf("word/media/a.png" to byteArrayOf(1), "word/media/b.png" to byteArrayOf(1)))
        val duplicate =
            bytes
                .toString(
                    Charsets.ISO_8859_1,
                ).replace("word/media/b.png", "word/media/a.png")
                .toByteArray(Charsets.ISO_8859_1)
        assertEquals("MALFORMED_DOCUMENT", assertThrows(DocumentException::class.java) { read(duplicate) }.code)
        val many = (1..2048).associate { "word/media/$it.bin" to byteArrayOf(1) }
        assertEquals(
            "RESOURCE_LIMIT",
            assertThrows(DocumentException::class.java) { read(pkg(paragraph("x"), many)) }.code,
        )
    }

    @Test fun aggregateInflationLimitAppliesToActualUncompressedBytes() {
        val data = ByteArray(17 * 1024 * 1024)
        val bytes = pkg(paragraph("x"), (1..4).associate { "word/media/$it.bin" to data })
        assertEquals("RESOURCE_LIMIT", assertThrows(DocumentException::class.java) { read(bytes) }.code)
    }
}
