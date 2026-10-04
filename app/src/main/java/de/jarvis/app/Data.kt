package de.jarvis.app

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "reminders")
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val triggerAt: Long,
    val repeat: String = "NONE", // NONE | DAILY | WEEKLY
    val asCall: Boolean = false,
    val done: Boolean = false
)

/** Memory-System (Phase 3): nur Eintraege, die der Nutzer sehen und loeschen kann. */
@Entity(tableName = "memories")
data class Memory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders WHERE done = 0 ORDER BY triggerAt") fun observeActive(): Flow<List<Reminder>>
    @Query("SELECT * FROM reminders WHERE done = 0") suspend fun active(): List<Reminder>
    @Query("SELECT * FROM reminders WHERE id = :id") suspend fun get(id: Long): Reminder?
    @Insert suspend fun insert(r: Reminder): Long
    @Update suspend fun update(r: Reminder)
    @Query("DELETE FROM reminders WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY createdAt DESC") fun observeAll(): Flow<List<Memory>>
    @Query("SELECT * FROM memories ORDER BY createdAt") suspend fun all(): List<Memory>
    @Insert suspend fun insert(m: Memory): Long
    @Query("DELETE FROM memories WHERE id = :id") suspend fun delete(id: Long)
}

@Database(entities = [Reminder::class, Memory::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun reminders(): ReminderDao
    abstract fun memories(): MemoryDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "jarvis.db").build().also { inst = it }
        }
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    /** FIRST_RUN == !setupDone */
    var setupDone: Boolean
        get() = sp.getBoolean("setup_done", false)
        set(v) { sp.edit().putBoolean("setup_done", v).apply() }
    var skipped: Set<String>
        get() = sp.getStringSet("skipped", emptySet()) ?: emptySet()
        set(v) { sp.edit().putStringSet("skipped", v).apply() }
}
