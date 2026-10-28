package com.example.ftmstreadmill.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** A saved workout. [json] is the exact export format, so import/export never needs a separate mapping. */
@Entity(tableName = "workouts")
data class WorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val json: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long? = null,
)

/** A finished (or ended early) workout run. JSON columns hold the plan and per-segment results. */
@Entity(tableName = "workout_sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: Long?,
    val workoutName: String,
    val startedAt: Long,
    val endedAt: Long,
    val activeSec: Int,
    val distanceMeters: Int?,
    val kcal: Int?,
    val completed: Boolean,
    val endReason: String,
    val workoutJson: String,
    val segmentsJson: String,
    val samplesJson: String,
    val healthConnectExported: Boolean = false,
)

@Dao
interface WorkoutDao {
    @Query("SELECT * FROM workouts ORDER BY COALESCE(lastUsedAt, updatedAt) DESC")
    fun observeAll(): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM workouts WHERE id = :id")
    fun observe(id: Long): Flow<WorkoutEntity?>

    @Query("SELECT * FROM workouts WHERE id = :id")
    suspend fun get(id: Long): WorkoutEntity?

    @Query("SELECT * FROM workouts WHERE id IN (:ids)")
    suspend fun getAll(ids: List<Long>): List<WorkoutEntity>

    @Query("SELECT * FROM workouts ORDER BY name")
    suspend fun getEverything(): List<WorkoutEntity>

    @Query("SELECT name FROM workouts")
    suspend fun names(): List<String>

    @Insert
    suspend fun insert(entity: WorkoutEntity): Long

    @Update
    suspend fun update(entity: WorkoutEntity)

    @Query("UPDATE workouts SET lastUsedAt = :time WHERE id = :id")
    suspend fun markUsed(id: Long, time: Long)

    @Query("DELETE FROM workouts WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM workout_sessions ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM workout_sessions WHERE id = :id")
    fun observe(id: Long): Flow<SessionEntity?>

    @Query("SELECT * FROM workout_sessions WHERE id = :id")
    suspend fun get(id: Long): SessionEntity?

    @Insert
    suspend fun insert(entity: SessionEntity): Long

    @Query("UPDATE workout_sessions SET healthConnectExported = 1 WHERE id = :id")
    suspend fun markExported(id: Long)

    @Query("DELETE FROM workout_sessions WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [WorkoutEntity::class, SessionEntity::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun workoutDao(): WorkoutDao
    abstract fun sessionDao(): SessionDao

    companion object {
        const val NAME = "ftms.db"
    }
}
