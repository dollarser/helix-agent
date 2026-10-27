package com.helix.app.terminal

import com.helix.app.internal.LineStore
import com.helix.app.proot.ExecutionOwnershipStore
import com.helix.tools.framework.ExecutionOwnership
import java.io.File

/** A tiny durable retry list bridges host settlement and Runtime removal; successful entries disappear. */
internal class TerminalReclamation(
    private val pending: LineStore,
    private val directory: File,
) {
    data class Entry(
        val fileName: String,
        val owner: ExecutionOwnership.Owner,
    )

    fun stage(
        binding: ExecutionOwnershipStore,
        owner: ExecutionOwnership.Owner,
    ) {
        synchronized(lock) {
            require(binding.file.parentFile == directory && binding.read() == owner)
            val entries = entries()
            val entry = Entry(binding.file.name, owner)
            if (entry !in entries) save(entries + entry)
        }
    }

    @Suppress("TooGenericExceptionCaught") // Keep retry identity for any failed IPC, filesystem or settlement step.
    fun retry(
        settle: (ExecutionOwnershipStore, ExecutionOwnership.Owner) -> Unit,
        removeRuntime: (ExecutionOwnership.Owner) -> Unit,
        failed: (Exception) -> Unit,
        completed: (ExecutionOwnership.Owner) -> Unit = {},
    ) = synchronized(lock) {
        entries().forEach { entry ->
            try {
                val binding = ExecutionOwnershipStore(File(directory, entry.fileName))
                val retained = binding.read()
                require(retained == null || retained == entry.owner) { "Terminal control identity changed" }
                if (retained != null) settle(binding, retained)
                removeRuntime(entry.owner)
                check(binding.read() == null) { "Terminal control identity changed" }
                check(
                    com.helix.runtime.proot.core.PtyStateFiles.removeMetadata(
                        directory,
                        entry.fileName,
                        java.nio.ByteBuffer
                            .allocate(8)
                            .putInt(1)
                            .putInt(0)
                            .array(),
                    ),
                ) { "Terminal control file changed" }
                completed(entry.owner)
                save(entries().filterNot { it == entry })
            } catch (cancel: java.util.concurrent.CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                failed(failure)
            }
        }
    }

    /** A refused start can leave only the empty control header, with no execution identity to settle. */
    fun discardEmpty(binding: ExecutionOwnershipStore) =
        synchronized(lock) {
            require(binding.file.parentFile == directory)
            if (binding.read() == null) {
                com.helix.runtime.proot.core.PtyStateFiles.removeMetadata(
                    directory,
                    binding.file.name,
                    java.nio.ByteBuffer
                        .allocate(8)
                        .putInt(1)
                        .putInt(0)
                        .array(),
                )
            }
        }

    private fun entries(): List<Entry> {
        val lines = pending.lines(KEY)
        if (lines.isEmpty()) return emptyList()
        require(lines.first() == "1" && (lines.size - 1) % 3 == 0)
        return lines.drop(1).chunked(3).map { (file, id, generation) ->
            require(file.matches(Regex("[A-Za-z0-9_-]+\\.owner")))
            require(listOf(id, generation).all { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) })
            Entry(file, ExecutionOwnership.Owner(id, generation))
        }
    }

    private fun save(entries: List<Entry>) {
        val lines =
            if (entries.isEmpty()) {
                emptyList()
            } else {
                listOf("1") +
                    entries.flatMap {
                        listOf(it.fileName, it.owner.executionId, it.owner.generation)
                    }
            }
        pending.setLines(KEY, lines)
        check(pending.lines(KEY) == lines) { "Terminal cleanup retry could not be persisted" }
    }

    private companion object {
        const val KEY = "pending"
        val lock = Any()
    }
}
