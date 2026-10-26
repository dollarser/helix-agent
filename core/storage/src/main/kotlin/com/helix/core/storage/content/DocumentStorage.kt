package com.helix.core.storage.content

/** User documents, not executable paths or grants. A resource reference never grants Agent access. */
interface DocumentReadAccess {
    fun list(directory: String): List<DocumentStorage.File>

    fun read(
        reference: String,
        maxBytes: Int,
    ): DocumentStorage.File
}

interface DocumentStorage : DocumentReadAccess {
    data class File(
        val reference: String,
        val name: String,
        val bytes: ByteArray,
        val hash: String,
    )

    override fun list(directory: String): List<File>

    fun references(directory: String): List<String>

    override fun read(
        reference: String,
        maxBytes: Int,
    ): File

    fun save(
        directory: String,
        name: String,
        bytes: ByteArray,
        previous: File? = null,
        mimeType: String = "text/markdown",
    ): File

    fun delete(previous: File)

    companion object {
        fun hash(bytes: ByteArray): String =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}

/** Failed-publication drafts are not committed content; explicit recovery consumes them. */
interface DocumentDraftRecovery {
    data class Draft(
        val token: String,
        val file: DocumentStorage.File,
    )

    fun drafts(): List<Draft>

    fun completeDraft(
        token: String,
        expectedHash: String,
    )
}

/** Explicit user open/share capability; private Agent history does not expose a URI. */
interface DocumentShareAccess {
    fun shareReference(reference: String): String
}
