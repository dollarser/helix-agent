package com.helix.app.files

import com.helix.core.workspace.FileScopePath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User navigation metadata only; never a file copy, scope grant or Agent context. Call on IO. */
class FileLibrary(
    private val read: () -> String? = { null },
    private val write: (String) -> Unit = {},
) {
    data class Entry(
        val reference: String,
        val directory: Boolean,
    ) {
        val path: FileScopePath get() = FileScopePath.fromModelReference(reference)
    }

    data class Snapshot(
        val version: Int = 1,
        val recent: List<Entry> = emptyList(),
        val favorites: List<Entry> = emptyList(),
    )

    private val mutable = MutableStateFlow(Snapshot())
    val state = mutable.asStateFlow()
    private var loaded = false

    @Synchronized
    fun load() {
        if (loaded) return
        val snapshot = read()?.let { FileLibraryCodec.decode(it) } ?: Snapshot()
        require(
            snapshot.version == 1 && snapshot.recent.size <= RECENT_LIMIT && snapshot.favorites.size <= FAVORITE_LIMIT,
        )
        (snapshot.recent + snapshot.favorites).forEach { require(it.path.toModelReference() == it.reference) }
        require(snapshot.recent.distinctBy { it.reference }.size == snapshot.recent.size)
        require(snapshot.favorites.distinctBy { it.reference }.size == snapshot.favorites.size)
        mutable.value = snapshot
        loaded = true
    }

    @Synchronized
    fun visited(
        path: FileScopePath,
        directory: Boolean,
    ) {
        load()
        val entry = Entry(path.toModelReference(), directory)
        val next = listOf(entry) + mutable.value.recent.filterNot { it.reference == entry.reference }
        save(mutable.value.copy(recent = next.take(RECENT_LIMIT)))
    }

    @Synchronized
    fun toggleFavorite(
        path: FileScopePath,
        directory: Boolean,
    ) {
        load()
        val entry = Entry(path.toModelReference(), directory)
        val old = mutable.value.favorites
        val next =
            if (old.any { it.reference == entry.reference }) {
                old.filterNot { it.reference == entry.reference }
            } else {
                check(old.size < FAVORITE_LIMIT)
                listOf(entry) + old
            }
        save(mutable.value.copy(favorites = next))
    }

    @Synchronized
    fun forget(entry: Entry) {
        load()
        save(
            mutable.value.copy(
                recent = mutable.value.recent.filterNot { it.reference == entry.reference },
                favorites = mutable.value.favorites.filterNot { it.reference == entry.reference },
            ),
        )
    }

    private fun save(next: Snapshot) {
        write(FileLibraryCodec.encode(next))
        mutable.value = next
    }

    companion object {
        const val RECENT_LIMIT = 40
        const val FAVORITE_LIMIT = 100
    }
}
