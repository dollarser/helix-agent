package com.helix.extensions.documents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.InputStream

/** Bounded OOXML body reader. Production invokes this only inside the isolated document worker. */
object DocxReader {
    fun read(
        source: InputStream,
        operation: String,
        startBlock: Int = 1,
        imageId: String? = null,
    ): DocumentResult {
        require(startBlock > 0 && operation in setOf("inspect", "read", "view"))
        val pack = DocxPackageReader.read(source)
        val body = DocxBody(startBlock, operation == "read", pack.relations)
        DocxXml.parse(pack.parts.getValue(pack.main), DocxNamespaces.WORD to "document", body)
        checkDocument(body.bodySeen, "MALFORMED_DOCUMENT")
        if (operation == "read") checkDocument(startBlock <= body.count, "RANGE_OUT_OF_BOUNDS")
        val image = if (operation == "view") image(pack, body, imageId) else null
        return DocumentResult(metadata(pack, body, operation, startBlock, imageId), image)
    }

    private fun image(
        pack: DocxPackage,
        body: DocxBody,
        imageId: String?,
    ): ByteArray {
        checkDocument(imageId != null && imageId in body.imageIds, "RANGE_OUT_OF_BOUNDS")
        val relation = pack.relations[imageId] ?: throw DocumentException("UNSUPPORTED_OBJECT")
        checkDocument(
            !relation.external && relation.type == "${DocxNamespaces.RELATIONSHIP}/image",
            "UNSUPPORTED_OBJECT",
        )
        val bytes =
            pack.parts[DocxPackageReader.partTarget(pack.main, relation.target)]
                ?: throw DocumentException("UNSUPPORTED_OBJECT")
        checkDocument(png(bytes) || jpeg(bytes), "UNSUPPORTED_OBJECT")
        checkDocument(bytes.size <= DocumentLimits.IMAGE_BYTES, "RESOURCE_LIMIT")
        return bytes
    }

    private fun metadata(
        pack: DocxPackage,
        body: DocxBody,
        operation: String,
        startBlock: Int,
        imageId: String?,
    ): JsonObject =
        buildJsonObject {
            put("status", if (operation == "inspect") "INSPECTED" else "CONTENT_READ")
            put("blockCount", body.count)
            put("imageCount", body.imageIds.size)
            put(
                "coverage",
                when (operation) {
                    "read" -> (
                        "body blocks $startBlock..${body.lastReturned}; headers, notes, comments and " +
                            "Word pagination excluded"
                    )

                    "view" -> "embedded image $imageId"

                    else -> "body structure only; no document prose returned"
                },
            )
            put("truncated", body.truncated)
            put("partial", true)
            putJsonArray("warnings") {
                add(
                    (
                        "DOCX body structure only; no Word page layout, headers, notes or revision " +
                            "rendering."
                    ),
                )
                pack.warnings.forEach(::add)
                body.warnings.forEach(::add)
            }
            if (operation == "read") {
                put("blocks", JsonArray(body.blocks))
                if (body.lastReturned < body.count) put("nextCursor", body.lastReturned + 1)
            }
            if (operation == "view") put("imageId", imageId!!)
        }

    private fun png(bytes: ByteArray): Boolean =
        bytes.size >= 8 &&
            bytes.take(8).toByteArray().contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))

    private fun jpeg(bytes: ByteArray): Boolean =
        bytes.size >= 3 &&
            bytes[0] == (-1).toByte() && bytes[1] == (-40).toByte() && bytes[2] == (-1).toByte()
}
