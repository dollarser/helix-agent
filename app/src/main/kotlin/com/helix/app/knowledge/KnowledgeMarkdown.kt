package com.helix.app.knowledge

import com.helix.core.storage.knowledge.KnowledgeNote
import com.helix.core.storage.knowledge.KnowledgeNoteSource

/** Export only saved user text and frozen evidence; never promotes an unreviewed generated draft. */
internal fun knowledgeMarkdown(
    note: KnowledgeNote,
    sources: List<KnowledgeNoteSource>,
): String =
    buildString {
        appendLine("# ${note.title.replace('\n', ' ')}")
        appendLine()
        appendLine("Status: ${note.state}; updated: ${java.time.Instant.ofEpochMilli(note.updatedAt)}")
        appendLine()
        appendLine(note.body)
        appendLine()
        appendLine("## Frozen references")
        sources.forEach { source ->
            appendLine()
            appendLine("### ${source.title.replace('\n', ' ')}")
            appendLine("Source ID: ${source.sourceId}")
            appendLine("URL: ${source.url}")
            appendLine("Content SHA256: ${source.contentHash}")
            appendLine("Status: ${source.completeness}")
            appendLine("Received: ${java.time.Instant.ofEpochMilli(source.receivedAt)}")
            appendLine()
            source.excerpt.lineSequence().forEach { appendLine("> $it") }
        }
    }
