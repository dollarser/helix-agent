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

    data class Entry(
        val reference: String,
        val name: String,
    )

    /** Path-backed stores can derive the display name. Opaque references require provider metadata. */
    fun entries(directory: String): List<Entry> =
        references(directory).map { reference ->
            require(reference.substringBeforeLast('/', "") == directory)
            Entry(reference, reference.substringAfterLast('/'))
        }

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

/** Trusted publication rollback for a durable, randomly named creation intent; never a tool grant. */
interface DocumentPendingCleanup {
    fun discardOwnedPending(
        directory: String,
        expectedName: String,
    )

    companion object {
        /** MediaStore can replace/append a MIME extension or add a collision suffix to the owned stem. */
        fun matchesOwnedName(
            expectedName: String,
            actualName: String,
        ): Boolean {
            val stem = expectedName.substringBeforeLast('.')
            if (!actualName.startsWith(stem)) return false
            val suffix = actualName.substring(stem.length)
            return suffix.isEmpty() || suffix.startsWith('.') || collisionSuffix.matches(suffix)
        }

        private val collisionSuffix = Regex(" \\([0-9]+\\)\\..+")
    }
}

/** Explicit user open/share capability; private Agent history does not expose a URI. */
interface DocumentShareAccess {
    fun shareReference(reference: String): String
}
