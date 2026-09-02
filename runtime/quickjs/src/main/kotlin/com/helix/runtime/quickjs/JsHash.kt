package com.helix.runtime.quickjs

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** SHA-256 helpers shared by the service, the client and the JVM tests (doc 03 §4.8 audit fields). */
object JsHash {
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256Utf8(text: String): String = sha256Hex(text.toByteArray(StandardCharsets.UTF_8))
}
