package com.example.ftmstreadmill.data

import android.util.Log
import com.example.ftmstreadmill.data.db.SessionDao
import com.example.ftmstreadmill.data.db.SessionEntity
import com.example.ftmstreadmill.workout.io.WorkoutFormatException
import com.example.ftmstreadmill.workout.io.WorkoutJson
import com.example.ftmstreadmill.workout.model.EndReason
import com.example.ftmstreadmill.workout.model.SegmentResult
import com.example.ftmstreadmill.workout.model.Session
import com.example.ftmstreadmill.workout.model.SpeedSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HistoryRepository @Inject constructor(
    private val dao: SessionDao,
) {
    val sessions: Flow<List<Session>> = dao.observeAll().map { list -> list.map { it.toSession() } }

    fun observe(id: Long): Flow<Session?> = dao.observe(id).map { it?.toSession() }

    suspend fun get(id: Long): Session? = dao.get(id)?.toSession()

    /** Stores [session] (its id is ignored) and returns the new id. */
    suspend fun insert(session: Session): Long = dao.insert(
        SessionEntity(
            workoutId = session.workoutId,
            workoutName = session.workoutName,
            startedAt = session.startedAt,
            endedAt = session.endedAt,
            activeSec = session.activeSec,
            distanceMeters = session.distanceMeters,
            kcal = session.kcal,
            completed = session.completed,
            endReason = session.endReason.name,
            workoutJson = session.workout?.let(WorkoutJson::encode).orEmpty(),
            segmentsJson = storage.encodeToString(segmentsSerializer, session.segments),
            samplesJson = storage.encodeToString(samplesSerializer, session.samples),
            healthConnectExported = session.healthConnectExported,
        ),
    )

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun markExported(id: Long) = dao.markExported(id)

    private fun SessionEntity.toSession() = Session(
        id = id,
        workoutId = workoutId,
        workoutName = workoutName,
        startedAt = startedAt,
        endedAt = endedAt,
        activeSec = activeSec,
        distanceMeters = distanceMeters,
        kcal = kcal,
        endReason = runCatching { EndReason.valueOf(endReason) }.getOrDefault(EndReason.ENDED_IN_APP),
        workout = try {
            workoutJson.takeIf { it.isNotBlank() }?.let(WorkoutJson::decodeSingle)
        } catch (e: WorkoutFormatException) {
            null
        },
        segments = decodeOrEmpty(segmentsJson) { storage.decodeFromString(segmentsSerializer, it) },
        samples = decodeOrEmpty(samplesJson) { storage.decodeFromString(samplesSerializer, it) },
        healthConnectExported = healthConnectExported,
    )

    private fun <T> decodeOrEmpty(text: String, decode: (String) -> List<T>): List<T> = try {
        if (text.isBlank()) emptyList() else decode(text)
    } catch (e: SerializationException) {
        Log.w(TAG, "Unreadable session data", e)
        emptyList()
    }

    private companion object {
        const val TAG = "HistoryRepository"
        val storage = Json { ignoreUnknownKeys = true }
        val segmentsSerializer = ListSerializer(SegmentResult.serializer())
        val samplesSerializer = ListSerializer(SpeedSample.serializer())
    }
}
