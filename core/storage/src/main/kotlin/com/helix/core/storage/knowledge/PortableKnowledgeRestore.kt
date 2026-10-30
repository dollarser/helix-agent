package com.helix.core.storage.knowledge

import com.helix.core.storage.content.DocumentReadAccess
import com.helix.core.storage.content.DocumentStorage

/** Domain validation/publication against an application-owned transactional document adapter. */
object PortableKnowledgeRestore {
    suspend fun preflight(
        source: DocumentReadAccess,
        target: DocumentStorage,
        originalsIncluded: Boolean,
    ): Int = KnowledgeLibraryRestore(target, originalsIncluded).restore(source, publish = false)

    suspend fun publish(
        source: DocumentReadAccess,
        target: DocumentStorage,
        originalsIncluded: Boolean,
    ): Int = KnowledgeLibraryRestore(target, originalsIncluded).restore(source)
}
