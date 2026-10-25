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

    @Query("SELECT * FROM knowledge WHERE state IN ('IMPORTING', 'EXTRACTING', 'QUEUED', 'DELETING')")
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

    @Query(
        "SELECT knowledge.* FROM knowledge JOIN knowledge_fts ON knowledge.id = knowledge_fts.id " +
            "WHERE knowledge_fts MATCH :query ORDER BY receivedAt DESC LIMIT 80",
    )
    suspend fun candidates(query: String): List<CollectedSource>

    @Query(
        "SELECT * FROM knowledge WHERE instr(lower(title || rawText || extracted), lower(:query)) > 0 " +
            "ORDER BY receivedAt DESC LIMIT 24",
    )
    suspend fun candidateSubstring(query: String): List<CollectedSource>
}

@Database(
    entities = [
        CollectedSource::class, KnowledgeSearch::class, KnowledgeNote::class,
        KnowledgeNoteSource::class, KnowledgeNoteSearch::class, KnowledgeAnswer::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class KnowledgeDatabase : RoomDatabase() {
    abstract fun items(): KnowledgeDao

    abstract fun notes(): KnowledgeNoteDao

    abstract fun answers(): KnowledgeAnswerDao
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
    private val answerDao = database.answers()
    val items = dao.observe()
    val notes = noteDao.observe()
    val answers = answerDao.observe()

    suspend fun answer(id: String) = answerDao.get(id)

    suspend fun saveAnswer(answer: KnowledgeAnswer) = answerDao.save(answer)

    suspend fun recoverAnswers() = answerDao.recover()

    suspend fun saveAnswerAsNote(
        id: String,
        sources: List<KnowledgeNoteSource>,
    ): String =
        database.withTransaction {
            val answer = requireNotNull(answerDao.get(id))
            check(answer.state == "READY")
            if (answer.savedNoteId.isNotEmpty()) return@withTransaction answer.savedNoteId
            val noteId =
                java.util.UUID
                    .randomUUID()
                    .toString()
            val now = System.currentTimeMillis()
            saveNote(
                KnowledgeNote(noteId, answer.question.take(160), answer.body, now, now),
                sources.map {
                    it.copy(noteId = noteId)
                },
            )
            answerDao.save(answer.copy(savedNoteId = noteId))
            noteId
        }

    suspend fun retrievalCandidates(terms: List<String>): List<CollectedSource> {
        require(terms.isNotEmpty() && terms.size <= 24 && terms.all { it.isNotBlank() && it.length <= 200 })
        val query = terms.joinToString(" OR ") { "\"${it.replace("\"", "") }\"*" }
        val candidates = dao.candidates(query).associateByTo(linkedMapOf()) { it.id }
        for (term in terms) {
            if (candidates.size >= 128) break
            dao.candidateSubstring(term).forEach { source ->
                if (candidates.size < 128) candidates.putIfAbsent(source.id, source)
            }
        }
        return candidates.values.toList()
    }

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

    suspend fun refreshNote(
        expected: KnowledgeNote,
        frozen: List<KnowledgeNoteSource>,
        currentSources: List<CollectedSource>,
        updated: KnowledgeNote,
        replacements: List<KnowledgeNoteSource>,
    ) = database.withTransaction {
        check(noteDao.get(expected.id) == expected && noteDao.sources(expected.id) == frozen)
        check(currentSources.all { dao.get(it.id) == it }) { "Source changed during reference refresh" }
        saveNote(updated, replacements)
    }

    suspend fun removeNote(expected: KnowledgeNote) =
        database.withTransaction {
            check(
                noteDao.get(expected.id) == expected && expected.generationState != "RUNNING",
            ) { "Note changed or generating" }
            noteDao.clearIndex(expected.id)
            noteDao.unlink(expected.id)
            answerDao.detachNote(expected.id)
            noteDao.delete(expected.id)
        }

    suspend fun boundedNoteSearch(
        query: String,
        limit: Int = 16,
    ): List<KnowledgeNote> {
        require(query.isNotBlank() && query.length <= 200 && limit in 1..16)
        return noteDao.boundedSearch(query, limit)
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
