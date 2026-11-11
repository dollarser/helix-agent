package com.helix.feature.browser.snapshot

import android.util.Base64
import android.webkit.MimeTypeMap
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** File payload is JSON data, not an executable fragment; no global bridge is installed. */
internal object BrowserUploadScript {
    fun preflight(
        index: Int,
        pageUrl: String,
        marker: String,
    ): String = script(index, pageUrl, "", null, marker)

    fun attach(
        index: Int,
        pageUrl: String,
        name: String,
        bytes: ByteArray,
        marker: String,
    ): String = script(index, pageUrl, name, bytes, marker)

    private fun script(
        index: Int,
        pageUrl: String,
        name: String,
        bytes: ByteArray?,
        marker: String,
    ): String {
        val payload =
            buildJsonObject {
                put("url", pageUrl)
                put("marker", marker)
                put("name", name)
                put(
                    "mime",
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                        ?: "application/octet-stream",
                )
                put("attach", bytes != null)
                put("base64", bytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) }.orEmpty())
            }
        return "(function(payload) { return " +
            BrowserActionScript.guardedNodeScript(index, BODY.replace("__SNAPSHOT__", BrowserSnapshotScript.EXTRACT)) +
            "})($payload);"
    }

    private val BODY =
        """
        function result(status, reason) {
          return {v:1,status:status,nodeIndex:__NODE_INDEX__,tag:tag,role:role,type:type,
                  autocomplete:ac,nameId:nameId,placeholder:ph,reason:reason};
        }
        if (String(location.href) !== payload.url) return result('refused','page-url-changed');
        if (refuse !== '') return result('refused',refuse);
        if (tag !== 'input' || tl !== 'file' || target.disabled || target.webkitdirectory) {
          return result('refused','enabled-single-file-input-required');
        }
        if (!payload.attach) {
          var snapshot = __SNAPSHOT__
          document[payload.marker] = target;
          setTimeout(function(){delete document[payload.marker];},10000);
          var outcome = result('performed','');
          outcome.snapshot = snapshot;
          return outcome;
        }
        var boundTarget = document[payload.marker];
        delete document[payload.marker];
        if (boundTarget !== target || !document.contains(target)) return result('refused','document-or-target-changed');
        var accepts = String(target.accept || '').toLowerCase().split(',').map(function(x){return x.trim();});
        if (accepts.some(function(x){return x !== '';}) && !accepts.some(function(rule){
          return rule === '*/*' || rule === payload.mime ||
            (rule.charAt(0) === '.' && payload.name.toLowerCase().endsWith(rule)) ||
            (rule.endsWith('/*') && payload.mime.indexOf(rule.slice(0,-1)) === 0);
        })) return result('refused','file-type-not-accepted');
        var raw = atob(payload.base64);
        var bytes = new Uint8Array(raw.length);
        for (var i=0;i<raw.length;i++) bytes[i]=raw.charCodeAt(i);
        var transfer = new DataTransfer();
        transfer.items.add(new File([bytes],payload.name,{type:payload.mime}));
        target.files = transfer.files;
        target.dispatchEvent(new Event('input',{bubbles:true}));
        target.dispatchEvent(new Event('change',{bubbles:true}));
        return result('performed','');
        })();
        """.trimIndent()
}
