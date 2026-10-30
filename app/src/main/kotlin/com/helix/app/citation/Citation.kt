package com.helix.app.citation

import java.net.URI

/** Navigation data only. Clicking a citation never authorizes a model read or executes an action. */
data class Citation(
    val kind: String,
    val id: String,
    val messageId: String?,
    val revision: String,
    val start: Int,
    val end: Int,
    val fileIndex: Int? = null,
    val page: Int? = null,
) {
    fun excerpt(text: String): String {
        val count = text.codePointCount(0, text.length)
        require(start in 0..count && end in start..count)
        return text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end))
    }

    companion object {
        private fun query(uri: URI): Map<String, String> {
            val fields = requireNotNull(uri.rawQuery).split('&').map { it.split('=') }
            require(
                fields.all { it.size == 2 } && fields.map { it[0] }.distinct().size == fields.size,
            )
            val query = fields.associate { it[0] to it[1] }
            require(
                query.keys == setOf("revision", "range") ||
                    query.keys == setOf("revision", "range", "file", "page"),
            )
            return query
        }

        private fun range(value: String): List<Int> {
            val range = value.split(':').map(String::toInt)
            require(
                range.size == 2 && range[0] >= 0 && range[1] >= range[0] &&
                    range[1].toLong() - range[0] <= 20_000,
            )
            return range
        }

        fun parse(value: String): Citation? =
            try {
                require(value.length <= 1024)
                val uri = URI(value)
                require(!uri.isOpaque && uri.rawPath != null)
                require(uri.scheme == "helix" && uri.rawUserInfo == null && uri.port == -1 && uri.fragment == null)
                val parts = uri.rawPath.removePrefix("/").split('/')
                require(parts.size == 2 && parts.all { it.matches(Regex("[A-Za-z0-9_-]{1,100}")) })
                val query = query(uri)
                val file = query["file"]?.toInt()?.also { require(it in 0..7) }
                val page = query["page"]?.toInt()?.also { require(it in 1..1_000_000) }
                val revision = query.getValue("revision")
                require(revision.matches(Regex("[a-f0-9]{64}")))
                val range = range(query.getValue("range"))
                when (uri.host) {
                    "knowledge" -> {
                        require(parts[0] in setOf("source", "note"))
                        require(file == null || parts[0] == "source")
                        Citation(parts[0], parts[1], null, revision, range[0], range[1], file, page)
                    }

                    "session" -> {
                        require(file == null)
                        Citation("session", parts[0], parts[1], revision, range[0], range[1])
                    }

                    else -> {
                        null
                    }
                }
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: java.net.URISyntaxException) {
                null
            }
    }
}

data class CitationContent(
    val title: String,
    val excerpt: String,
    val page: Int? = null,
    val noText: Boolean = false,
)
