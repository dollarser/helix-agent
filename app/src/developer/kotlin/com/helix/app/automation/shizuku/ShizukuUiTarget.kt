package com.helix.app.automation.shizuku

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/** Exact selector; no arbitrary shell arguments or persisted coordinates. */
internal data class ShizukuUiSelector(
    val packageName: String,
    val resourceId: String,
    val text: String,
) {
    init {
        require(packageName.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+")))
        require(resourceId.matches(Regex("[a-zA-Z][a-zA-Z0-9_.]*:id/[a-zA-Z0-9_]+")))
        require(text.isNotBlank() && text.length <= 256)
    }
}

internal data class ShizukuUiTarget(
    val rotation: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val x: Int get() = left + (right - left) / 2
    val y: Int get() = top + (bottom - top) / 2

    companion object {
        fun resolve(
            xml: String,
            selector: ShizukuUiSelector,
        ): ShizukuUiTarget {
            require(
                xml.length <= MAX_XML_CHARS && !xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true),
            ) {
                "UNSAFE_OR_OVERSIZED_HIERARCHY"
            }
            val factory = DocumentBuilderFactory.newInstance().apply { isExpandEntityReferences = false }
            val root = factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
            require(root.tagName == "hierarchy") { "INVALID_HIERARCHY" }
            require(root.getAttribute("rotation") in setOf("0", "1", "2", "3")) { "INVALID_ROTATION" }
            val nodes = root.getElementsByTagName("node")
            val matches =
                (0 until nodes.length).map { nodes.item(it) as Element }.filter {
                    it.getAttribute("package") == selector.packageName &&
                        it.getAttribute("resource-id") == selector.resourceId &&
                        it.getAttribute("text") == selector.text
                }
            check(matches.size == 1) { if (matches.isEmpty()) "TARGET_NOT_FOUND" else "TARGET_AMBIGUOUS" }
            val node = matches.single()
            check(node.getAttribute("enabled") == "true" && node.getAttribute("clickable") == "true") {
                "TARGET_NOT_ACTIONABLE"
            }
            check(node.getAttribute("password") != "true") { "SENSITIVE_TARGET" }
            val bounds = Regex("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]").matchEntire(node.getAttribute("bounds"))
            requireNotNull(bounds) { "INVALID_BOUNDS" }
            val values = bounds.groupValues.drop(1).map(String::toInt)
            val target = ShizukuUiTarget(root.getAttribute("rotation"), values[0], values[1], values[2], values[3])
            require(
                target.right > target.left && target.bottom > target.top &&
                    target.right <= MAX_COORDINATE && target.bottom <= MAX_COORDINATE,
            ) { "INVALID_BOUNDS" }
            return target
        }

        const val MAX_XML_CHARS = 256 * 1024
        private const val MAX_COORDINATE = 32_768
    }
}
