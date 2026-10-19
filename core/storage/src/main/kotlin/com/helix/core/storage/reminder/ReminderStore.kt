package com.helix.core.storage.reminder

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
import com.helix.core.model.ReminderRepeat
import com.helix.core.model.ReminderTime
import java.time.LocalDateTime

@Entity(tableName = "reminders")
data class LocalReminder(
    @PrimaryKey val id: String,
    val title: String,
    val body: String,
    val localTime: String,
    val zone: String,
    val repeat: String,
    val exact: Boolean,
    val nextAt: Long?,
    val revision: Long = 1,
    val state: String = "ACTIVE",
    val scheduling: String = "PENDING",
    val lastOutcome: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun time() = ReminderTime(LocalDateTime.parse(localTime), zone, ReminderRepeat.valueOf(repeat))
}

/** One durable fact per revision/occurrence; POSTED means OS notify accepted, never user-read. */
@Entity(tableName = "reminder_events", primaryKeys = ["reminderId", "revision", "dueAt"])
data class ReminderEvent(
    val reminderId: String,
    val revision: Long,
    val dueAt: Long,
    val outcome: String,
    val recordedAt: Long,
)

@Dao
interface ReminderDao {
    @Query("DELETE FROM reminder_events WHERE reminderId = :id")
    suspend fun deleteEvents(id: String)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM reminders ORDER BY updatedAt DESC")
    fun observe(): kotlinx.coroutines.flow.Flow<List<LocalReminder>>

    @Query("SELECT * FROM reminders")
    suspend fun all(): List<LocalReminder>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun get(id: String): LocalReminder?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(item: LocalReminder)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun event(event: ReminderEvent)

    @Query("SELECT * FROM reminder_events WHERE reminderId = :id ORDER BY recordedAt DESC LIMIT 30")
    suspend fun events(id: String): List<ReminderEvent>

    @Query("SELECT * FROM reminder_events WHERE reminderId = :id AND revision = :revision AND dueAt = :due")
    suspend fun eventAt(
        id: String,
        revision: Long,
        due: Long,
    ): ReminderEvent?

    @Query("SELECT * FROM reminder_events WHERE outcome = 'POSTING'")
    suspend fun uncertain(): List<ReminderEvent>
}

@Database(entities = [LocalReminder::class, ReminderEvent::class], version = 1, exportSchema = true)
abstract class ReminderDatabase : RoomDatabase() {
    abstract fun reminders(): ReminderDao
}

class ReminderStore(
    context: Context,
    name: String = "reminders.db",
) {
    private val database = Room.databaseBuilder(context.applicationContext, ReminderDatabase::class.java, name).build()
    val dao = database.reminders()

    suspend fun <T> transaction(block: suspend () -> T): T = database.withTransaction(block)

    fun close() = database.close()
}
