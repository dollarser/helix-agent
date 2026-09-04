package com.helix.feature.browser.snapshot

/**
 * The FIXED, versioned DOM-extraction script for `browser.snapshot` (HXA-061; doc 09 §3.4:
 * “DOM 提取和动作通过 Helix 固定、版本化的 JavaScript 片段执行；模型不能提交任意脚本给
 * WebView”).
 *
 * [EXTRACT] is the ONLY JavaScript the browser feature ever evaluates on a page. It is a
 * compile-time constant: no page value, user input or model input is ever interpolated
 * into it, so a hostile page cannot steer the script. The script is self-contained and
 * read-only (no network, no storage, no bridge access — it only reads its own document's
 * light DOM), and it enforces the SAME bounds the Kotlin side re-verifies: a hostile
 * document cannot make the evaluation return an unbounded payload or hang the response
 * channel, and any drift between the two sides fails the [BrowserSnapshotScriptTest]
 * pin below.
 *
 * The page's document is UNTRUSTED web content (doc 09 §3.4). The script's output is
 * DATA for the trusted host to bind, fingerprint and mark untrusted — never code the
 * host executes.
 */
object BrowserSnapshotScript {
    /** Script version; the result object carries it and the host rejects any other value. */
    const val SCRIPT_VERSION = 1

    // The bounds below are duplicated as literals inside [EXTRACT] (the script must be a
    // self-contained constant and cannot read Kotlin values). BrowserSnapshotScriptTest
    // pins the two sides together, so a one-sided change fails the gate.
    const val MAX_NODES = 400
    const val MAX_TEXT_LENGTH = 200
    const val MAX_VISITED_ELEMENTS = 20000

    /**
     * Walks `document.body` in document order and returns (as the last expression value)
     * an Array of bounded semantic node objects. Interactive / landmark elements are kept;
     * everything else is descended into. Password field VALUES are never read.
     */
    val EXTRACT: String =
        """
        (function () {
          var MAX_NODES = 400;
          var MAX_TEXT = 200;
          var MAX_VISITED = 20000;
          function cleanText(s) {
            if (s === null || s === undefined) return "";
            s = String(s).replace(/\\s+/g, " ").trim();
            if (s.length > MAX_TEXT) s = s.substring(0, MAX_TEXT);
            return s;
          }
          function kindOf(el) {
            var t = String(el.tagName || "").toLowerCase();
            if (t === "a" && el.hasAttribute("href")) return "link";
            if (t === "button") return "button";
            if (t === "img") return "image";
            if (/^h[1-6]$/.test(t)) return "heading";
            if (t === "input" || t === "select" || t === "textarea") {
              if (String(el.getAttribute("type") || "").toLowerCase() === "hidden") return null;
              return "field";
            }
            var r = String(el.getAttribute("role") || "").toLowerCase();
            if (r === "button" || r === "link" || r === "textbox" || r === "checkbox" ||
                r === "radio" || r === "heading" || r === "menuitem" || r === "option") {
              return "interactive";
            }
            return null;
          }
          function nameOf(el) {
            return cleanText(el.getAttribute("aria-label") || el.getAttribute("name") ||
              el.getAttribute("placeholder") || el.getAttribute("title") || el.id || "");
          }
          function textOf(el) {
            var alt = String(el.getAttribute("alt") || "").trim();
            if (alt !== "") return cleanText(alt);
            return cleanText(el.textContent);
          }
          function valueOf(el) {
            var t = String(el.tagName || "").toLowerCase();
            if (t === "input") {
              // doc 09 §3.3: 密码框默认拒绝 —— the value of a password field is never read.
              if (String(el.getAttribute("type") || "").toLowerCase() === "password") return null;
            }
            if (t === "select") {
              var opt = el.selectedIndex >= 0 ? el.options[el.selectedIndex] : null;
              return opt ? cleanText(opt.textContent) : null;
            }
            var v = el.value;
            if (v === null || v === undefined) return null;
            v = cleanText(v);
            return v === "" ? null : v;
          }
          var nodes = [];
          var visited = 0;
          var truncated = false;
          function walk(parent) {
            var kids = parent.children;
            if (!kids) return;
            for (var i = 0; i < kids.length; i++) {
              if (nodes.length >= MAX_NODES) { truncated = true; return; }
              var el = kids[i];
              if (++visited > MAX_VISITED) { truncated = true; return; }
              if (el.hidden) continue;
              var kind = kindOf(el);
              if (kind !== null) {
                var href = null;
                if (el.hasAttribute("href")) href = cleanText(el.getAttribute("href"));
                nodes.push({
                  i: nodes.length,
                  tag: String(el.tagName || "").toLowerCase(),
                  role: kind,
                  text: textOf(el),
                  value: valueOf(el),
                  href: href,
                  name: nameOf(el)
                });
              }
              if (el.children && el.children.length > 0) walk(el);
            }
          }
          var body = document ? document.body : null;
          if (!body || !body.children) return { v: 1, truncated: false, nodes: [] };
          walk(body);
          return { v: 1, truncated: truncated, nodes: nodes };
        })();
        """.trimIndent()
}
