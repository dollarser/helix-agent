package com.helix.tools.framework

import com.helix.core.model.ToolBindingRef
import com.helix.core.model.ToolName
import com.helix.core.model.ToolVersion
import java.util.UUID

/** The sole atomic publication owner. ToolRegistry is its public catalog and admission facade. */
internal class ToolBindingStore(
    private val hostRevision: String,
) {
    private val publicationLock = Any()
    private val lock = Any()
    private var preparingPublication = false
    private var entries = emptyMap<Pair<ToolName, ToolVersion>, RegisteredToolBinding>()

    fun snapshot(): List<RegisteredToolBinding> = synchronized(lock) { entries.values.toList() }

    fun register(candidates: List<ToolBinding>): List<RegisteredToolBinding> =
        synchronized(publicationLock) {
            check(!preparingPublication) { "recursive binding publication is not allowed" }
            synchronized(lock) {
                validate(candidates, entries)
                val published = candidates.map(::create)
                entries = entries + published.associateBy { it.ref.name to it.ref.version }
                published
            }
        }

    fun replace(
        owner: String,
        candidates: List<ToolBinding>,
        beforePublish: () -> Unit,
    ): List<RegisteredToolBinding> =
        synchronized(publicationLock) {
            check(!preparingPublication) { "recursive binding publication is not allowed" }
            val (next, published) =
                synchronized(lock) {
                    require(candidates.all { it.owner == owner }) { "binding owner mismatch" }
                    val retained = entries.filterValues { it.ref.owner != owner }
                    validate(candidates, retained)
                    val published =
                        candidates.map { candidate ->
                            entries[candidate.descriptor.name to candidate.descriptor.version]
                                ?.takeIf {
                                    it.executor === candidate.executor &&
                                        it.binding.implementationRevision == candidate.implementationRevision &&
                                        it.descriptor == candidate.descriptor
                                } ?: create(candidate)
                        }
                    val next = retained + published.associateBy { it.ref.name to it.ref.version }
                    next to published
                }
            // Local durable preparation may wait on storage; admission and readers remain unlocked.
            // All publishers serialize here, so a successful preparation cannot lose a CAS race.
            preparingPublication = true
            try {
                beforePublish()
            } finally {
                preparingPublication = false
            }
            synchronized(lock) { entries = next }
            published
        }

    fun resolve(
        name: ToolName,
        version: ToolVersion,
    ): RegisteredToolBinding? = synchronized(lock) { entries[name to version] }

    fun resolve(ref: ToolBindingRef): RegisteredToolBinding? =
        synchronized(lock) { entries[ref.name to ref.version]?.takeIf { it.ref == ref } }

    /** Only a short local admission commit is allowed here, never executor/approval/network work. */
    fun <T : Any> admit(
        ref: ToolBindingRef,
        commit: () -> T,
    ): T? =
        synchronized(lock) {
            if (entries[ref.name to ref.version]?.ref == ref) commit() else null
        }

    private fun validate(
        candidates: List<ToolBinding>,
        retained: Map<Pair<ToolName, ToolVersion>, RegisteredToolBinding>,
    ) {
        val keys = candidates.map { it.descriptor.name to it.descriptor.version }
        require(keys.distinct().size == keys.size) { "duplicate binding in batch" }
        require(keys.none { it in retained }) { "duplicate binding collision; original snapshot retained" }
    }

    private fun create(source: ToolBinding): RegisteredToolBinding {
        val candidate = source.copy(descriptor = source.descriptor.snapshot())
        return RegisteredToolBinding(
            candidate,
            ToolBindingRef(
                candidate.descriptor.name,
                candidate.descriptor.version,
                candidate.descriptor.contractHash.hex,
                candidate.owner,
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(
                        kotlinx.serialization.json
                            .JsonArray(
                                listOf(hostRevision, candidate.implementationRevision)
                                    .map { kotlinx.serialization.json.JsonPrimitive(it) },
                            ).toString()
                            .toByteArray(Charsets.UTF_8),
                    ).joinToString("") { "%02x".format(it) },
                UUID.randomUUID().toString(),
            ),
        )
    }
}
