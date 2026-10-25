package com.helix.core.storage.knowledge

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A user-authored or explicitly reviewed synthesis, independent of collected sources. */
@Entity(tableName = "knowledge_notes")
data class KnowledgeNote(
    @PrimaryKey val id: String,
    val title: String,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
    val state: String = "DRAFT",
    val generatedDraft: String = "",
    val generationState: String = "IDLE",
    val generationTarget: String = "",
    @androidx.room.ColumnInfo(defaultValue = "'NOTE'") val kind: String = "NOTE",
    @androidx.room.ColumnInfo(defaultValue = "'[]'") val originReferencesJson: String = "[]",
    @androidx.room.ColumnInfo(defaultValue = "''") val originSessionId: String = "",
    @androidx.room.ColumnInfo(defaultValue = "''") val originMessageId: String = "",
    @androidx.room.ColumnInfo(defaultValue = "''") val originContentHash: String = "",
)

/** Frozen evidence selected by the user. Source re-extraction never changes saved note evidence. */
@Entity(tableName = "knowledge_note_sources", primaryKeys = ["noteId", "sourceId"])
data class KnowledgeNoteSource(
    val noteId: String,
    val sourceId: String,
    val title: String,
    val url: String,
    val receivedAt: Long,
    val excerpt: String,
    val contentHash: String,
    val completeness: String,
)

@Fts4
@Entity(tableName = "knowledge_notes_fts")
data class KnowledgeNoteSearch(
    val id: String,
    val text: String,
)

@Dao
@Suppress("TooManyFunctions") // A single notes persistence boundary, including its frozen source links.
interface KnowledgeNoteDao {
    @Query("SELECT * FROM knowledge_notes ORDER BY updatedAt DESC")
    fun observe(): Flow<List<KnowledgeNote>>

    @Query("SELECT * FROM knowledge_notes WHERE id = :id")
    suspend fun get(id: String): KnowledgeNote?

    @Query("SELECT * FROM knowledge_note_sources WHERE noteId = :id ORDER BY sourceId")
    suspend fun sources(id: String): List<KnowledgeNoteSource>

    @Query("SELECT * FROM knowledge_notes WHERE generationState = 'RUNNING'")
    suspend fun interrupted(): List<KnowledgeNote>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(note: KnowledgeNote)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun link(sources: List<KnowledgeNoteSource>)

    @Query("DELETE FROM knowledge_notes WHERE id = :id")
    suspend fun delete(id: String)

    @Query(
        "SELECT * FROM knowledge_notes WHERE instr(lower(title || body), lower(:query)) > 0 " +
            "ORDER BY updatedAt DESC LIMIT :limit",
    )
    suspend fun boundedSearch(
        query: String,
        limit: Int,
    ): List<KnowledgeNote>

    @Query("DELETE FROM knowledge_note_sources WHERE noteId = :id")
    suspend fun unlink(id: String)

    @Query("DELETE FROM knowledge_notes_fts WHERE id = :id")
    suspend fun clearIndex(id: String)

    @Insert
    suspend fun index(value: KnowledgeNoteSearch)

    @Query(
        "SELECT knowledge_notes.* FROM knowledge_notes JOIN knowledge_notes_fts ON " +
            "knowledge_notes.id = knowledge_notes_fts.id " +
            "WHERE knowledge_notes_fts MATCH :query ORDER BY updatedAt DESC",
    )
    suspend fun search(query: String): List<KnowledgeNote>

    @Query("SELECT * FROM knowledge_notes WHERE instr(lower(title || body), lower(:query)) > 0 ORDER BY updatedAt DESC")
    suspend fun substring(query: String): List<KnowledgeNote>
}
