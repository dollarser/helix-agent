package com.helix.app.knowledge

/** Content identity shared by saved notes, fixed references and retained provenance. */
internal fun knowledgeHash(text: String): String =
    java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
