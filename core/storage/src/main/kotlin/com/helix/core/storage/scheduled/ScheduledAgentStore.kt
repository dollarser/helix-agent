package com.helix.core.storage.scheduled

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/** Only a confirmed local user action creates/enables a rule. A clock event supplies no authority. */
@Entity(tableName = "scheduled_rules")
data class ScheduledAgentRule(
    @PrimaryKey val id: String,
    val title: String,
    val prompt: String,
    val scopeJson: String,
    val scopeHash: String,
    val sessionId: String,
    val localTime: String,
    val zone: String,
    val repeat: String,
    val revision: Long = 1,
    val enabled: Boolean = false,
    val nextAt: Long? = null,
    val status: String = "PAUSED",
    val creator: String = "LOCAL_USER",
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Stable calendar occurrence and its immutable authorization/result destination. */
@Entity(tableName = "scheduled_occurrences", primaryKeys = ["ruleId", "revision", "dueAt"])
data class ScheduledOccurrence(
    val ruleId: String,
    val revision: Long,
    val dueAt: Long,
    val ruleJson: String,
    val outcome: String,
    val recordedAt: Long,
    val lastAttemptId: String = "",
    val skippedThrough: Long? = null,
)

/** Every explicit retry gets a new identity; no attempt is revived after death or missing ACK. */
@Entity(tableName = "scheduled_attempts")
data class ScheduledAttempt(
    @PrimaryKey val id: String,
    val ruleId: String,
    val revision: Long,
    val dueAt: Long,
    val sessionId: String,
    val state: String,
    val createdAt: Long,
    val turnId: String = "",
    val completedAt: Long? = null,
    val detail: String = "",
)

@Dao
@Suppress("TooManyFunctions") // One rule/occurrence/attempt ledger with immutable occurrence ownership.
interface ScheduledAgentDao {
    @Query("SELECT * FROM scheduled_rules ORDER BY updatedAt DESC")
    fun observeRules(): Flow<List<ScheduledAgentRule>>

    @Query("SELECT * FROM scheduled_occurrences ORDER BY recordedAt DESC LIMIT 100")
    fun observeOccurrences(): Flow<List<ScheduledOccurrence>>

    @Query("SELECT * FROM scheduled_attempts ORDER BY createdAt DESC LIMIT 200")
    fun observeAttempts(): Flow<List<ScheduledAttempt>>

    @Query("SELECT * FROM scheduled_rules")
    suspend fun rules(): List<ScheduledAgentRule>

    @Query("SELECT * FROM scheduled_rules WHERE id = :id")
    suspend fun rule(id: String): ScheduledAgentRule?

    @Query("SELECT * FROM scheduled_occurrences WHERE ruleId = :id AND revision = :revision AND dueAt = :due")
    suspend fun occurrence(
        id: String,
        revision: Long,
        due: Long,
    ): ScheduledOccurrence?

    @Query("SELECT * FROM scheduled_attempts WHERE id = :id")
    suspend fun attempt(id: String): ScheduledAttempt?

    @Query(
        "SELECT * FROM scheduled_attempts WHERE state IN " +
            "('ADMITTING', 'RUNNING', 'STOP_REQUESTED', 'WAITING', 'UNKNOWN')",
    )
    suspend fun unfinished(): List<ScheduledAttempt>

    @Query(
        "SELECT COUNT(*) FROM scheduled_attempts WHERE sessionId = :session AND " +
            "state IN ('ADMITTING', 'RUNNING', 'STOP_REQUESTED', 'WAITING', 'UNKNOWN')",
    )
    suspend fun activeForSession(session: String): Int

    @Query("SELECT COUNT(*) FROM scheduled_attempts WHERE ruleId = :id AND revision = :revision AND dueAt = :due")
    suspend fun attemptsAt(
        id: String,
        revision: Long,
        due: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(rule: ScheduledAgentRule)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(occurrence: ScheduledOccurrence)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(attempt: ScheduledAttempt)
}

@Database(
    entities = [ScheduledAgentRule::class, ScheduledOccurrence::class, ScheduledAttempt::class],
    version = 1,
    exportSchema = true,
)
abstract class ScheduledAgentDatabase : RoomDatabase() {
    abstract fun ledger(): ScheduledAgentDao
}

class ScheduledAgentStore(
    context: Context,
    name: String = "scheduled-agents.db",
) {
    private val database =
        Room
            .databaseBuilder(
                context.applicationContext,
                ScheduledAgentDatabase::class.java,
                name,
            ).build()
    val dao = database.ledger()

    suspend fun <T> transaction(block: suspend () -> T): T = database.withTransaction(block)

    fun close() = database.close()
}
