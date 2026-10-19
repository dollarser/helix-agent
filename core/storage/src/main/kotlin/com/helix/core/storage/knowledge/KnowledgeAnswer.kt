package com.helix.core.storage.knowledge

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A generated answer is neither a collected source nor a user-reviewed note. */
@Entity(tableName = "knowledge_answers")
data class KnowledgeAnswer(
    @PrimaryKey val id: String,
    val question: String,
    val createdAt: Long,
    val state: String,
    val sourcesJson: String,
    val targetJson: String,
    val body: String = "",
    val savedNoteId: String = "",
)

@Dao
interface KnowledgeAnswerDao {
    @Query("SELECT * FROM knowledge_answers ORDER BY createdAt DESC")
    fun observe(): Flow<List<KnowledgeAnswer>>

    @Query("SELECT * FROM knowledge_answers WHERE id = :id")
    suspend fun get(id: String): KnowledgeAnswer?

    @Query("UPDATE knowledge_answers SET state = 'INTERRUPTED' WHERE state = 'RUNNING'")
    suspend fun recover()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(answer: KnowledgeAnswer)
}
