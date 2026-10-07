package com.meshalert.app.db

import androidx.room.*
import com.meshalert.app.model.AlertType
import com.meshalert.app.model.MeshMessage
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun getAllMessages(): Flow<List<MeshMessage>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MeshMessage): Long  // returns -1 if duplicate (ignored)

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    @Query("DELETE FROM messages WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}

class AlertTypeConverter {
    @TypeConverter fun fromAlertType(value: AlertType): String = value.name
    @TypeConverter fun toAlertType(value: String): AlertType = AlertType.valueOf(value)
}

@Database(entities = [MeshMessage::class], version = 1, exportSchema = false)
@TypeConverters(AlertTypeConverter::class)
abstract class MeshDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile private var INSTANCE: MeshDatabase? = null

        fun getInstance(context: android.content.Context): MeshDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context, MeshDatabase::class.java, "mesh.db")
                    .build().also { INSTANCE = it }
            }
    }
}
