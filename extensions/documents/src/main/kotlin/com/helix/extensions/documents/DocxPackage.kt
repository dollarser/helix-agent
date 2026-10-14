package com.helix.extensions.documents

import org.xml.sax.Attributes
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.util.zip.ZipInputStream

internal data class DocxRelation(
    val type: String,
    val target: String,
    val external: Boolean,
)

internal data class DocxPackage(
    val parts: Map<String, ByteArray>,
    val main: String,
    val relations: Map<String, DocxRelation>,
    val warnings: List<String>,
)

/** No archive entry is ever extracted to a filesystem path or fetched from the network. */
internal object DocxPackageReader {
    private const val MAIN_TYPE = ("application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml")

    fun read(source: InputStream): DocxPackage {
        val parts = readMembers(source)
        val types = readTypes(parts["[Content_Types].xml"] ?: throw DocumentException("UNSUPPORTED_FORMAT"))
        val root = relations(parts["_rels/.rels"] ?: throw DocumentException("MALFORMED_DOCUMENT"))
        val mainRelation =
            root.values.singleOrNull {
                it.type == "${DocxNamespaces.RELATIONSHIP}/officeDocument" &&
                    !it.external
            }
        checkDocument(mainRelation != null, "UNSUPPORTED_FORMAT")
        val main = partTarget("root.xml", mainRelation!!.target)
        checkDocument(types[main] == MAIN_TYPE && main in parts, "UNSUPPORTED_FORMAT")
        val prefix = main.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val relPath = prefix + "_rels/" + main.substringAfterLast('/') + ".rels"
        val rels = parts[relPath]?.let(::relations).orEmpty()
        rels.values.filterNot { it.external }.forEach { partTarget(main, it.target) }
        val warnings = mutableListOf<String>()
        if (rels.values.any { it.external }) warnings += "External relationships are not fetched."
        if (parts.keys.any { it.endsWith("vbaProject.bin", true) || it.contains("/embeddings/") }) {
            warnings += "Macro/embedded objects are not executed or rendered."
        }
        return DocxPackage(parts, main, rels, warnings)
    }

    private fun readMembers(source: InputStream): Map<String, ByteArray> {
        val parts = linkedMapOf<String, ByteArray>()
        val names = hashSetOf<String>()
        val budget = UnzipBudget()
        ZipInputStream(source).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                checkDocument(names.size < 2048, "RESOURCE_LIMIT")
                checkDocument(safeEntry(entry.name) && names.add(entry.name), "MALFORMED_DOCUMENT")
                if (!entry.isDirectory) parts[entry.name] = budget.read(zip, entry.name)
            }
        }
        return parts
    }

    private class UnzipBudget {
        private var total = 0

        fun read(
            zip: ZipInputStream,
            name: String,
        ): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            val xml = name.endsWith(".xml") || name.endsWith(".rels")
            val limit = if (xml) DocxXml.MAX_BYTES else DocumentLimits.SOURCE_BYTES.toInt()
            while (true) {
                val count = zip.read(buffer)
                if (count < 0) break
                checkDocument(count > 0 && count <= 64 * 1024 * 1024 - total, "RESOURCE_LIMIT")
                checkDocument(count <= limit - output.size(), "RESOURCE_LIMIT")
                total += count
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    private fun safeEntry(name: String): Boolean {
        if (name.isBlank() || name.length > 1024 || name.startsWith('/')) return false
        val allowedCharacters = name.none { it == '\\' || it == ':' || it == '\u0000' }
        return allowedCharacters && name.removeSuffix("/").split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    fun partTarget(
        main: String,
        target: String,
    ): String {
        val uri =
            try {
                URI(target)
            } catch (
                _: java.net.URISyntaxException,
            ) {
                throw DocumentException("MALFORMED_DOCUMENT")
            }
        checkDocument(!uri.isAbsolute && uri.rawAuthority == null && uri.rawQuery == null, "MALFORMED_DOCUMENT")
        checkDocument(
            uri.rawFragment == null && uri.rawPath == uri.path && !target.contains('\\'),
            "MALFORMED_DOCUMENT",
        )
        val normalized =
            URI("/$main")
                .resolve(uri)
                .normalize()
                .path
                .removePrefix("/")
        checkDocument(safeEntry(normalized), "MALFORMED_DOCUMENT")
        return normalized
    }

    private fun attribute(
        attributes: Attributes,
        name: String,
    ): String = attributes.getValue(name) ?: throw SAXException("Missing $name")

    private fun readTypes(bytes: ByteArray): Map<String, String> {
        val types = hashMapOf<String, String>()
        DocxXml.parse(
            bytes,
            DocxNamespaces.TYPES to "Types",
            object : DefaultHandler2() {
                override fun startElement(
                    uri: String,
                    localName: String,
                    qName: String,
                    attributes: Attributes,
                ) {
                    if (uri == DocxNamespaces.TYPES && localName == "Override") {
                        val part = attribute(attributes, "PartName")
                        val type = attribute(attributes, "ContentType")
                        if (types.put(part.removePrefix("/"), type) !=
                            null
                        ) {
                            throw SAXException("Duplicate content type")
                        }
                    }
                }
            },
        )
        return types
    }

    private fun relations(bytes: ByteArray): Map<String, DocxRelation> {
        val result = linkedMapOf<String, DocxRelation>()
        DocxXml.parse(
            bytes,
            DocxNamespaces.PACKAGE to "Relationships",
            object : DefaultHandler2() {
                override fun startElement(
                    uri: String,
                    localName: String,
                    qName: String,
                    attributes: Attributes,
                ) {
                    if (uri == DocxNamespaces.PACKAGE && localName == "Relationship") {
                        val id = attribute(attributes, "Id")
                        val type = attribute(attributes, "Type")
                        val target = attribute(attributes, "Target")
                        val mode = attributes.getValue("TargetMode")
                        checkDocument(mode == null || mode in setOf("Internal", "External"), "MALFORMED_DOCUMENT")
                        checkDocument(result.size < 8192 && id.length in 1..128, "RESOURCE_LIMIT")
                        if (result.put(id, DocxRelation(type, target, mode == "External")) !=
                            null
                        ) {
                            throw SAXException("Duplicate relationship")
                        }
                    }
                }
            },
        )
        return result
    }
}
