package com.helix.app.knowledge

import com.helix.core.storage.knowledge.CollectedSource
import com.helix.core.storage.knowledge.KnowledgeNoteSource

/** Deterministic local lexical retrieval; no model request, network access or hidden library context. */
internal object KnowledgeRetrieval {
    private val words = Regex("[A-Za-z0-9_]+|[\\p{IsHan}]+")
    private val stop =
        setOf("what", "which", "where", "when", "who", "how", "the", "and", "is", "are", "of", "in", "a", "to", "for")

    fun terms(question: String): List<String> =
        words
            .findAll(question.take(200))
            .flatMap { match ->
                val word = match.value.lowercase(java.util.Locale.ROOT)
                when {
                    word in stop -> emptySequence()
                    word.first().code in 0x4e00..0x9fff && word.length > 2 -> word.windowed(2).asSequence()
                    else -> sequenceOf(word)
                }
            }.distinct()
            .take(24)
            .toList()

    fun rank(
        candidates: List<CollectedSource>,
        terms: List<String>,
    ): List<CollectedSource> =
        candidates
            .map { source ->
                val content = source.title + "\n" + source.extracted.ifBlank { source.rawText }
                val text = content.lowercase(java.util.Locale.ROOT)
                val title = source.title.lowercase(java.util.Locale.ROOT)
                val score =
                    terms.sumOf { term ->
                        (if (text.contains(term)) 1 else 0) + (if (title.contains(term)) 2 else 0)
                    }
                source to score
            }.filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<CollectedSource, Int>> { it.second }.thenBy { it.first.id },
            ).take(16)
            .map { it.first }

    fun evidence(
        source: CollectedSource,
        question: String,
        budget: Int,
    ): KnowledgeNoteSource {
        val text = source.extracted.ifBlank { source.rawText }
        val positions =
            terms(question)
                .mapNotNull { term -> text.indexOf(term, ignoreCase = true).takeIf { it >= 0 } }
                .sorted()
        val ranges = mutableListOf<IntRange>()
        val window = budget / 3
        for (position in positions.ifEmpty { listOf(0) }) {
            if (ranges.none { position in it }) {
                var start = (position - window / 4).coerceAtLeast(0)
                if (start > 0 && text[start].isLowSurrogate()) start--
                var end = (start + window).coerceAtMost(text.length)
                if (end < text.length && end > 0 && text[end - 1].isHighSurrogate()) end--
                if (end > start) ranges.add(start until end)
            }
            if (ranges.size == 3) break
        }
        val excerpt =
            ranges.joinToString("\n[…]\n") { range ->
                "[characters ${range.first}..${range.last + 1} of ${text.length}]\n" +
                    text.substring(range.first, range.last + 1)
            }
        return KnowledgeNoteSource(
            "",
            source.id,
            source.title.take(160),
            source.sourceUrl.take(2000),
            source.receivedAt,
            excerpt,
            knowledgeHash(text),
            "${source.state}:${source.detail}; bounded excerpts, not full source",
        )
    }
}
