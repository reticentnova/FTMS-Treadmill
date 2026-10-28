package com.example.ftmstreadmill.data

import android.content.Context
import android.util.Log
import com.example.ftmstreadmill.data.db.WorkoutDao
import com.example.ftmstreadmill.data.db.WorkoutEntity
import com.example.ftmstreadmill.workout.io.WorkoutFormatException
import com.example.ftmstreadmill.workout.io.WorkoutJson
import com.example.ftmstreadmill.workout.model.Workout
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class SavedWorkout(
    val id: Long,
    val workout: Workout,
    val updatedAt: Long,
    val lastUsedAt: Long?,
)

/** A built-in workout bundled in `assets/presets`. [id] is the file name. */
data class PresetWorkout(val id: String, val workout: Workout)

/** Where the workout shown on a detail screen comes from. */
sealed interface WorkoutSource {
    data class Saved(val id: Long) : WorkoutSource
    data class Preset(val id: String) : WorkoutSource
}

@Singleton
class WorkoutRepository @Inject constructor(
    private val dao: WorkoutDao,
    @ApplicationContext private val context: Context,
) {
    val workouts: Flow<List<SavedWorkout>> = dao.observeAll().map { list -> list.mapNotNull { it.toSaved() } }

    fun observe(id: Long): Flow<SavedWorkout?> = dao.observe(id).map { it?.toSaved() }

    suspend fun get(id: Long): SavedWorkout? = dao.get(id)?.toSaved()

    /** Inserts when [id] is null, otherwise overwrites. Returns the id. */
    suspend fun save(id: Long?, workout: Workout, now: Long = System.currentTimeMillis()): Long {
        WorkoutJson.validate(workout)
        val json = WorkoutJson.encode(workout)
        val existing = id?.let { dao.get(it) }
        return if (existing != null) {
            dao.update(existing.copy(name = workout.name, json = json, updatedAt = now))
            existing.id
        } else {
            dao.insert(WorkoutEntity(name = workout.name, json = json, createdAt = now, updatedAt = now))
        }
    }

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun markUsed(id: Long) = dao.markUsed(id, System.currentTimeMillis())

    /** Saves a copy named so it doesn't clash with an existing workout. */
    suspend fun saveCopy(workout: Workout): Long = save(null, workout.copy(name = uniqueName(workout.name)))

    /** Parses [text] and adds every workout in it. Returns the number imported. */
    suspend fun import(text: String): Int {
        val workouts = withContext(Dispatchers.Default) { WorkoutJson.decode(text) }
        workouts.forEach { saveCopy(it) }
        return workouts.size
    }

    suspend fun exportJson(ids: Collection<Long>): String {
        val workouts = dao.getAll(ids.toList()).mapNotNull { it.toSaved()?.workout }
        return WorkoutJson.encodeAll(workouts)
    }

    suspend fun exportAllJson(): String = WorkoutJson.encodeAll(dao.getEverything().mapNotNull { it.toSaved()?.workout })

    suspend fun presets(): List<PresetWorkout> = withContext(Dispatchers.IO) {
        presetCache ?: loadPresets().also { presetCache = it }
    }

    suspend fun preset(id: String): PresetWorkout? = presets().firstOrNull { it.id == id }

    @Volatile private var presetCache: List<PresetWorkout>? = null

    private fun loadPresets(): List<PresetWorkout> {
        val files = context.assets.list(PRESET_DIR).orEmpty().filter { it.endsWith(".json") }.sorted()
        return files.mapNotNull { file ->
            try {
                val text = context.assets.open("$PRESET_DIR/$file").bufferedReader().use { it.readText() }
                PresetWorkout(file, WorkoutJson.decodeSingle(text))
            } catch (e: WorkoutFormatException) {
                Log.e(TAG, "Bad preset $file", e)
                null
            }
        }
    }

    private suspend fun uniqueName(name: String): String {
        val taken = dao.names().toSet()
        if (name !in taken) return name
        var n = 2
        while ("$name ($n)" in taken) n++
        return "$name ($n)"
    }

    private fun WorkoutEntity.toSaved(): SavedWorkout? = try {
        SavedWorkout(id, WorkoutJson.decodeSingle(json), updatedAt, lastUsedAt)
    } catch (e: WorkoutFormatException) {
        Log.e(TAG, "Stored workout $id is unreadable", e)
        null
    }

    private companion object {
        const val TAG = "WorkoutRepository"
        const val PRESET_DIR = "presets"
    }
}
