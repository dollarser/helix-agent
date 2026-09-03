package com.helix.feature.browser.snapshot

import kotlinx.serialization.json.JsonPrimitive

/**
 * The FIXED, versioned action scripts for `browser.click` / `browser.type` / `browser.scroll`
 * (HXA-062; doc 09 §3.4: “DOM 提取和动作通过 Helix 固定、版本化的 JavaScript 片段执行；模型不能
 * 提交任意脚本给 WebView”).
 *
 * Like [BrowserSnapshotScript.EXTRACT], these are the ONLY JavaScript the browser feature
 * ever runs on a page. A model value is interpolated in exactly ONE way and only as a
 * validated, self-contained literal: a node index / scroll delta is a host-validated Int, and
 * the `type` text is injected as a JSON string literal (a JSON string literal is a valid JS
 * string literal, so it cannot break out of the script). Everything else — including the
 * sensitive-field rules — is a compile-time constant, so a hostile page cannot steer the
 * script.
 *
 * [click] and [type] replicate the [BrowserSnapshotScript.EXTRACT] walk verbatim so the
 * `nodeIndex` the host minted in the last snapshot maps to the same DOM element, re-read the
 * field's live attributes at that element, and apply the SAME sensitive-field policy the host
 * re-applies in :tools:browser (`SensitiveFieldClassifier`). An action is PERFORMED only when
 * both agree the field is normal (fail-closed). The two implementations are pinned to agree
 * by the classifier unit tests (Kotlin) and the on-device refusal test (JS).
 */
object BrowserActionScript {
    /** Script version; every result object carries it (the host does not re-check it for actions). */
    const val SCRIPT_VERSION = 1

    // The bounds below duplicate the EXTRACT constants so the walk keeps exactly the same
    // elements (and therefore the same nodeIndex → element mapping). BrowserActionScriptTest
    // pins them together, so a one-sided change fails the gate.
    const val MAX_NODES = 400
    const val MAX_VISITED_ELEMENTS = 20000

    /**
     * Shared IIFE head: the same bounds, `kindOf` and document-order walk as [EXTRACT], but
     * instead of collecting nodes it stops at the element whose kept-index equals
     * `__NODE_INDEX__` (spliced as a validated Int) and leaves it in `target`.
     */
    private val HEAD =
        """
        (function () {
          var MAX_NODES = 400;
          var MAX_VISITED = 20000;
          var PAYMENT_NAME = new RegExp(
            "card[-_]?number|cc[-_]?number|cv[vy]|credit[-_]?card|debit[-_]?card|bank[-_]?card|" +
            "pay[-_]?card|expiry|exp[-_]?(date|month|year|mm|yy|mo|yr)|iban", "i");
          var OTP_NAME = new RegExp(
            "otp|one[-_]?time[-_]?code|verification[-_]?code|verify[-_]?code|auth[-_]?code|" +
            "sms[-_]?code|mfa[-_]?code|2[-_]?fa|totp|captcha|passcode|secret[-_]?code|device[-_]?code", "i");
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
          var target = null;
          var visited = 0;
          var seen = 0;
          function walk(parent) {
            var kids = parent.children;
            if (!kids) return;
            for (var i = 0; i < kids.length; i++) {
              if (seen >= MAX_NODES) return;
              var el = kids[i];
              if (++visited > MAX_VISITED) return;
              if (el.hidden) continue;
              var kind = kindOf(el);
              if (kind !== null) {
                if (seen === __NODE_INDEX__) target = el;
                seen++;
              }
              if (el.children && el.children.length > 0) walk(el);
            }
          }
          var body = document ? document.body : null;
          if (body && body.children) walk(body);
        """.trimIndent()

    /**
     * Shared classification tail: reports `not-found` when the walk matched nothing, else
     * re-reads the field's live (tag, type, autocomplete, name/id, placeholder) and computes
     * `refuse` with the EXACT rules of [com.helix.tools.browser.SensitiveFieldClassifier]
     * (password → payment → one-time-code, first match wins; bare “code” is not a trigger).
     */
    private val CLASSIFY =
        """
        if (!target) {
          return { v: 1, status: "not-found", nodeIndex: __NODE_INDEX__, tag: "", role: "",
                   type: "", autocomplete: "", nameId: "", placeholder: "", reason: "" };
        }
        var tag = String(target.tagName || "").toLowerCase();
        var role = kindOf(target) || "";
        var type = String(target.getAttribute("type") || "");
        var ac = String(target.getAttribute("autocomplete") || "");
        var nameId = String(target.getAttribute("name") || target.id || "");
        var ph = String(target.getAttribute("placeholder") || "");
        var tl = type.toLowerCase();
        var acl = ac.toLowerCase();
        var label = (nameId + " " + ph).toLowerCase();
        var refuse = "";
        if (tl === "password" || acl === "password" || acl.endsWith("-password")) refuse = "password";
        else if (acl.indexOf("cc-") === 0 || acl === "credit-card" || acl === "on-card" ||
                 PAYMENT_NAME.test(label)) refuse = "payment";
        else if (acl === "one-time-code") refuse = "one-time-code";
        else if ((tag === "input" || tag === "select" || tag === "textarea") && OTP_NAME.test(label)) refuse = "one-time-code";
        """.trimIndent()

    /** `browser.click`: focus + click the located element unless a sensitive-field gate refuses it. */
    private val CLICK_ACT =
        """
          var performed = false;
          if (refuse === "") {
            try {
              if (target.focus) target.focus();
              target.click();
              performed = true;
            } catch (e) {
              performed = false;
            }
          }
          return { v: 1, status: refuse !== "" ? "refused" : (performed ? "performed" : "error"),
                   nodeIndex: __NODE_INDEX__, tag: tag, role: role, type: type, autocomplete: ac,
                   nameId: nameId, placeholder: ph, reason: refuse };
        })();
        """.trimIndent()

    /** `browser.type`: set the field's value + fire input events; refuses sensitive / non-field. */
    private val TYPE_ACT =
        """
          var isField = (tag === "input" || tag === "select" || tag === "textarea");
          var performed = false;
          if (refuse === "") {
            if (!isField) {
              return { v: 1, status: "not-a-field", nodeIndex: __NODE_INDEX__, tag: tag, role: role,
                       type: type, autocomplete: ac, nameId: nameId, placeholder: ph, reason: "not-a-field" };
            }
            try {
              if (target.focus) target.focus();
              if (tag === "select") {
                target.value = TEXT;
                target.dispatchEvent(new Event("change", { bubbles: true }));
              } else {
                var proto = (tag === "input") ? window.HTMLInputElement.prototype
                                              : window.HTMLTextAreaElement.prototype;
                var d = Object.getOwnPropertyDescriptor(proto, "value");
                if (d && d.set) d.set.call(target, TEXT);
                else target.value = TEXT;
                target.dispatchEvent(new Event("input", { bubbles: true }));
                target.dispatchEvent(new Event("change", { bubbles: true }));
              }
              performed = true;
            } catch (e) {
              try {
                target.value = TEXT;
                performed = true;
              } catch (e2) {
                performed = false;
              }
            }
          }
          return { v: 1, status: refuse !== "" ? "refused" : (performed ? "performed" : "error"),
                   nodeIndex: __NODE_INDEX__, tag: tag, role: role, type: type, autocomplete: ac,
                   nameId: nameId, placeholder: ph, reason: refuse };
        })();
        """.trimIndent()

    /** `browser.scroll`: a bounded viewport scroll of the current page. */
    private val SCROLL_FIXED =
        """
        (function () {
          try {
            window.scrollBy(__DX__, __DY__);
            return { v: 1, ok: true, x: window.scrollX, y: window.scrollY };
          } catch (e) {
            return { v: 1, ok: false, x: 0, y: 0 };
          }
        })();
        """.trimIndent()

    /** Builds the `browser.click` script for the element at [nodeIndex] (a host-validated Int). */
    fun click(nodeIndex: Int): String = (HEAD + CLASSIFY + CLICK_ACT).replace("__NODE_INDEX__", nodeIndex.toString())

    /**
     * Builds the `browser.type` script: types [text] into the field at [nodeIndex]. [text] is
     * injected as a JSON string literal (its quotes / backslashes / newlines are neutralized),
     * so no page or model value can alter the script's structure.
     */
    fun type(
        nodeIndex: Int,
        text: String,
    ): String {
        val n = nodeIndex.toString()
        return HEAD.replace("__NODE_INDEX__", n) +
            "var TEXT = " + JsonPrimitive(text).toString() + ";" +
            CLASSIFY.replace("__NODE_INDEX__", n) +
            TYPE_ACT.replace("__NODE_INDEX__", n)
    }

    /** Builds the `browser.scroll` script for a viewport scroll of [dx] / [dy] CSS pixels. */
    fun scroll(
        dx: Int,
        dy: Int,
    ): String = SCROLL_FIXED.replace("__DX__", dx.toString()).replace("__DY__", dy.toString())
}
