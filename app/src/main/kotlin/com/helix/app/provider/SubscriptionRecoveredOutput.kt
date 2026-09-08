package com.helix.app.provider

/** Bounded pages of visible model text only; this result never drives tools or Goal completion. */
data class SubscriptionRecoveredOutput(
    val pages: List<String>,
) {
    companion object {
        private const val PAGE_CHARACTERS = 4096

        internal fun fromText(text: String): SubscriptionRecoveredOutput {
            val pages = mutableListOf<String>()
            var start = 0
            while (start < text.length) {
                var end = (start + PAGE_CHARACTERS).coerceAtMost(text.length)
                if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
                pages.add(text.substring(start, end))
                start = end
            }
            return SubscriptionRecoveredOutput(pages)
        }
    }
}
