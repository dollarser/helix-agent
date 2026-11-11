package com.helix.app.vision

import com.helix.app.internal.LineStore
import java.security.MessageDigest

internal data class MobileScreenSource(
    val scopeRef: String,
    val sessionId: String,
    val turnId: String,
    val sha256: String,
    val mediaType: String,
)

/** Host-owned durable provenance; content lookup never uses a model-provided filename. */
internal class MobileScreenSources(
    private val store: LineStore,
) {
    fun forArtifact(artifactId: String): MobileScreenSource? = read(artifactKey(artifactId))

    fun forContent(
        session: String,
        sha256: String,
        mediaType: String,
    ): MobileScreenSource? = read(contentKey(session, sha256, mediaType))

    fun rememberArtifact(
        artifactId: String,
        source: MobileScreenSource,
    ) {
        val previous = forArtifact(artifactId)
        check(previous == null || previous == source) { "Screen acquisition provenance is immutable" }
        if (previous == null) write(artifactKey(artifactId), source)
    }

    fun rememberContent(source: MobileScreenSource) =
        write(contentKey(source.sessionId, source.sha256, source.mediaType), source)

    private fun read(key: String): MobileScreenSource? {
        val fields = store.lines(key)
        if (fields.isEmpty()) return null
        check(fields.size == 6 && fields[0] == VERSION) { "Screen provenance is unreadable" }
        return MobileScreenSource(fields[1], fields[2], fields[3], fields[4], fields[5])
    }

    private fun write(
        key: String,
        source: MobileScreenSource,
    ) = store.setLines(
        key,
        listOf(VERSION, source.scopeRef, source.sessionId, source.turnId, source.sha256, source.mediaType),
    )

    private fun artifactKey(artifactId: String) = "mobile-screen-source-" + digest(artifactId)

    private fun contentKey(
        session: String,
        sha256: String,
        mediaType: String,
    ) = "mobile-screen-content-" + digest("$session:$sha256:$mediaType")

    private fun digest(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val VERSION = "mobile-screen-source-v1"
    }
}
