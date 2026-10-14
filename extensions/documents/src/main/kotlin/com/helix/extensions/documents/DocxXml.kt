package com.helix.extensions.documents

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2
import java.io.ByteArrayInputStream
import javax.xml.parsers.SAXParserFactory

internal object DocxNamespaces {
    const val WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    const val RELATIONSHIP = ("http://schemas.openxmlformats.org/officeDocument/2006/relationships")
    const val DRAWING = "http://schemas.openxmlformats.org/drawingml/2006/main"
    const val PACKAGE = "http://schemas.openxmlformats.org/package/2006/relationships"
    const val TYPES = "http://schemas.openxmlformats.org/package/2006/content-types"
}

internal fun checkDocument(
    condition: Boolean,
    code: String,
) {
    if (!condition) throw DocumentException(code)
}

/** Fail closed if a platform SAX implementation cannot install the DTD/entity guards. */
internal object DocxXml {
    const val MAX_BYTES = 8 * 1024 * 1024

    fun parse(
        bytes: ByteArray,
        expectedRoot: Pair<String, String>,
        content: DefaultHandler2,
    ) {
        checkDocument(bytes.size <= MAX_BYTES, "RESOURCE_LIMIT")
        try {
            val reader =
                SAXParserFactory
                    .newInstance()
                    .apply { isNamespaceAware = true }
                    .newSAXParser()
                    .xmlReader
            reader.setFeature("http://xml.org/sax/features/external-general-entities", false)
            reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            val guard = Guard(expectedRoot, content)
            reader.contentHandler = guard
            reader.entityResolver = guard
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", guard)
            reader.parse(InputSource(ByteArrayInputStream(bytes)))
        } catch (_: SAXException) {
            throw DocumentException("MALFORMED_DOCUMENT")
        } catch (_: javax.xml.parsers.ParserConfigurationException) {
            throw DocumentException("PARSER_UNAVAILABLE")
        }
    }

    private class Guard(
        private val expected: Pair<String, String>,
        private val content: DefaultHandler2,
    ) : DefaultHandler2() {
        private var depth = 0

        override fun startDTD(
            name: String?,
            publicId: String?,
            systemId: String?,
        ): Unit = throw SAXException("DTD forbidden")

        override fun resolveEntity(
            publicId: String?,
            systemId: String?,
        ): InputSource = throw SAXException("External entity forbidden")

        override fun startElement(
            uri: String,
            localName: String,
            qName: String,
            attributes: Attributes,
        ) {
            if (++depth > 64) throw SAXException("XML depth limit")
            if (depth == 1 &&
                (uri != expected.first || localName != expected.second)
            ) {
                throw SAXException("Unexpected XML root")
            }
            content.startElement(uri, localName, qName, attributes)
        }

        override fun endElement(
            uri: String,
            localName: String,
            qName: String,
        ) {
            content.endElement(uri, localName, qName)
            depth--
        }

        override fun characters(
            ch: CharArray,
            start: Int,
            length: Int,
        ) = content.characters(ch, start, length)
    }
}
