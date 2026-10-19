package com.helix.core.storage.knowledge

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/** Independent, additive library storage; opening it never resets or migrates conversation data. */
@Entity(tableName = "knowledge")
data class CollectedSource(
    @PrimaryKey val id: String,
    val title: String,
    val receivedAt: Long,
    val source: String,
    val sourceUrl: String,
    val rawText: String,
    val filesJson: String = "[]",
    val state: String = "IMPORTING",
    val extracted: String = "",
    val summary: String = "",
    val detail: String = "",
    val fingerprint: String = "",
)

@Fts4
@Entity(tableName = "knowledge_fts")
data class KnowledgeSearch(
    val id: String,
    val text: String,
)

@Dao
@Suppress("TooManyFunctions") // Cohesive persistence port for the independent collection.
interface KnowledgeDao {
    @Query("SELECT * FROM knowledge ORDER BY receivedAt DESC")
    fun observe(): Flow<List<CollectedSource>>

    @Query("SELECT * FROM knowledge WHERE id = :id")
    suspend fun get(id: String): CollectedSource?

    @Query("SELECT * FROM knowledge WHERE fingerprint = :fingerprint AND fingerprint != '' LIMIT 1")
    suspend fun duplicate(fingerprint: String): CollectedSource?

    @Query("SELECT * FROM knowledge WHERE state IN ('IMPORTING', 'EXTRACTING', 'QUEUED')")
    suspend fun unfinished(): List<CollectedSource>

    @Insert
    suspend fun insert(item: CollectedSource)

    @Update
    suspend fun update(item: CollectedSource)

    @Query("DELETE FROM knowledge WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM knowledge_fts WHERE id = :id")
    suspend fun clearIndex(id: String)

    @Insert
    suspend fun index(item: KnowledgeSearch)

    @Query(
        "SELECT knowledge.* FROM knowledge JOIN knowledge_fts ON knowledge.id = knowledge_fts.id " +
            "WHERE knowledge_fts MATCH :query ORDER BY receivedAt DESC",
    )
    suspend fun search(query: String): List<CollectedSource>

    @Query(
        "SELECT * FROM knowledge WHERE " +
            "instr(lower(title || rawText || sourceUrl || extracted || summary || filesJson), lower(:query)) > 0 " +
            "ORDER BY receivedAt DESC",
    )
    suspend fun substring(query: String): List<CollectedSource>
}

@Database(
    entities = [
        CollectedSource::class, KnowledgeSearch::class, KnowledgeNote::class,
        KnowledgeNoteSource::class, KnowledgeNoteSearch::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class KnowledgeDatabase : RoomDatabase() {
    abstract fun items(): KnowledgeDao

    abstract fun notes(): KnowledgeNoteDao
}

@Suppress("TooManyFunctions") // One transactional boundary for library sources, notes and evidence links.
class KnowledgeStore(
    context: Context,
    name: String = "library.db",
) {
    private val database =
        Room
            .databaseBuilder(
                context.applicationContext,
                KnowledgeDatabase::class.java,
                name,
            ).build()
    private val dao = database.items()
    private val noteDao = database.notes()
    val items = dao.observe()
    val notes = noteDao.observe()

    fun close() {
        database.close()
    }

    suspend fun get(id: String) = dao.get(id)

    suspend fun duplicate(fingerprint: String) = dao.duplicate(fingerprint)

    suspend fun unfinished() = dao.unfinished()

    suspend fun insert(item: CollectedSource) = dao.insert(item)

    suspend fun note(id: String) = noteDao.get(id)

    suspend fun noteSources(id: String) = noteDao.sources(id)

    suspend fun interruptedNotes() = noteDao.interrupted()

    suspend fun saveNote(
        note: KnowledgeNote,
        sources: List<KnowledgeNoteSource>? = null,
    ) {
        database.withTransaction {
            noteDao.save(note)
            if (sources != null) {
                noteDao.unlink(note.id)
                noteDao.link(sources)
            }
            noteDao.clearIndex(note.id)
            noteDao.index(KnowledgeNoteSearch(note.id, note.title + "\n" + note.body))
        }
    }

    suspend fun searchNotes(query: String): List<KnowledgeNote> {
        val tokens = Regex("[\\p{L}\\p{N}]+").findAll(query.take(200)).map { "\"${it.value}\"*" }.toList()
        return if (tokens.isEmpty()) {
            emptyList()
        } else {
            (
                noteDao.search(tokens.joinToString(" AND ")) + noteDao.substring(query.take(200))
            ).distinctBy { it.id }
        }
    }

    suspend fun save(item: CollectedSource) {
        database.withTransaction {
            dao.update(item)
            dao.clearIndex(item.id)
            dao.index(
                KnowledgeSearch(
                    item.id,
                    listOf(item.title, item.rawText, item.sourceUrl, item.extracted, item.summary, item.filesJson)
                        .joinToString("\n"),
                ),
            )
        }
    }

    suspend fun remove(id: String) {
        database.withTransaction {
            dao.clearIndex(id)
            dao.delete(id)
        }
    }

    suspend fun search(query: String): List<CollectedSource> {
        val tokens = Regex("[\\p{L}\\p{N}]+").findAll(query.take(200)).map { "\"${it.value}\"*" }.toList()
        return if (tokens.isEmpty()) {
            emptyList()
        } else {
            (
                dao.search(tokens.joinToString(" AND ")) +
                    dao.substring(query.take(200))
            ).distinctBy { it.id }
        }
    }
}
