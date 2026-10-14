package com.example.mystatus

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

@Entity(tableName = "status_events")
data class StatusEventEntity(
    @androidx.room.PrimaryKey val eventId: String,
    val occurredAt: Long,
    val type: String,
    val payload: String
)

@Dao
interface StatusEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(event: StatusEventEntity): Long

    @Query("SELECT * FROM status_events ORDER BY occurredAt ASC LIMIT :limit")
    fun pending(limit: Int): List<StatusEventEntity>

    @Query("DELETE FROM status_events WHERE eventId = :eventId")
    fun delete(eventId: String)

    @Query("SELECT COUNT(*) FROM status_events")
    fun count(): Int
}

@Database(entities = [StatusEventEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): StatusEventDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "mystatus.db"
                ).build().also { instance = it }
            }
    }
}

object EventQueue {
    private val executor = Executors.newSingleThreadExecutor()

    fun enqueue(context: Context, type: String, payload: JSONObject, occurredAt: Long = System.currentTimeMillis()) {
        if (!StatusStore.collectionEnabled(context)) return
        val event = EventFactory.create(type, payload, occurredAt)
        StatusStore.setLastUpdate(context, occurredAt)
        executor.execute {
            if (!StatusStore.collectionEnabled(context)) return@execute
            if (AppDatabase.get(context).eventDao().insert(event) != -1L) {
                UploadScheduler.enqueue(context)
            }
        }
    }
}

object EventFactory {
    fun create(type: String, payload: JSONObject, occurredAt: Long): StatusEventEntity =
        StatusEventEntity(UUID.randomUUID().toString(), occurredAt, type, payload.toString())
}
