package com.helix.feature.browser.snapshot

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Checks observed semantics in the action's own JS turn, without another WebView round trip. */
internal object BrowserActionTargetGuard {
    fun bind(
        script: String,
        node: SnapshotNode,
    ): String {
        require(script.contains(BrowserActionScript.TARGET_CHECK))
        val expected =
            buildJsonObject {
                put("tag", node.tag)
                put("role", node.role)
                put("text", node.text.text)
                put("value", node.value?.text)
                put("href", node.href?.text)
                put("name", node.name?.text)
            }
        val guard =
            """
            if (target && !(function(expected) {
              var MAX_TEXT = ${BrowserSnapshotScript.MAX_TEXT_LENGTH};
              ${BrowserSnapshotScript.NODE_HELPERS}
              return String(target.tagName || "").toLowerCase() === expected.tag &&
                kindOf(target) === expected.role && textOf(target) === expected.text &&
                valueOf(target) === expected.value && nameOf(target) === expected.name &&
                (target.hasAttribute("href") ? cleanText(target.getAttribute("href")) : null) === expected.href;
            })($expected)) {
              return {v:1,status:"stale-target",nodeIndex:${node.index},tag:"",role:"",
                      type:"",autocomplete:"",nameId:"",placeholder:"",reason:"stale-target"};
            }
            """.trimIndent()
        return script.replace(BrowserActionScript.TARGET_CHECK, guard)
    }
}
