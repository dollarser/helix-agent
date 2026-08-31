package com.helix.core.model

/**
 * Lowercase hex encoding, shared by every byte-to-hex site in the domain layer (plan hashes,
 * context content hashes, random id generation). One implementation so the encoders cannot
 * drift apart (different padStart/format/loop variants used to coexist).
 */
object Hex {
    fun encode(bytes: ByteArray): String {
        val chars = "0123456789abcdef".toCharArray()
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(chars[(b.toInt() shr 4) and 0x0F])
            sb.append(chars[b.toInt() and 0x0F])
        }
        return sb.toString()
    }
}
