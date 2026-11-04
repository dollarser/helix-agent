package com.helix.provider.api

/** Untrusted diagnostic text, never an instruction or a retry/permission decision. */
internal object ServerErrorSummary {
    fun redact(
        text: String,
        secrets: Collection<String>,
    ): String {
        var value = text.filter { !it.isISOControl() || it in "\n\t" }
        if (value.contains("PRIVATE KEY-----")) return "[redacted: private key]"
        secrets.filter { it.length >= 4 }.sortedByDescending { it.length }.forEach {
            value = value.replace(it, "[redacted: credential]")
        }
        patterns.forEach { value = it.replace(value, "[redacted: credential]") }
        return prefix(value)
    }

    private fun prefix(value: String): String {
        val bytes = value.toByteArray()
        if (bytes.size <= MAX_BYTES) return value
        var end = MAX_BYTES
        while (end > 0 && bytes[end].toInt() and 0xc0 == 0x80) end--
        return bytes.copyOf(end).toString(Charsets.UTF_8)
    }

    private const val MAX_BYTES = 768
    private val patterns =
        listOf(
            Regex("(?i)bearer\\s+[^\\s,;\\\"']+"),
            Regex("(?i)(?:authorization|api[-_]?key|token|password|cookie)\\s*[=:]\\s*[^\\s,;]+"),
            Regex("(?:sk-|gh[pousr]_|xox[baprs]-)[A-Za-z0-9_-]{8,}"),
            Regex("[A-Za-z0-9_~+/=.-]{20,}"),
        )
}
