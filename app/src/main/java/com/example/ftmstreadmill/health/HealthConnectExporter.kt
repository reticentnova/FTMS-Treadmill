package com.example.ftmstreadmill.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Velocity
import com.example.ftmstreadmill.data.HistoryRepository
import com.example.ftmstreadmill.data.SettingsRepository
import com.example.ftmstreadmill.di.ApplicationScope
import com.example.ftmstreadmill.workout.WorkoutEngine
import com.example.ftmstreadmill.workout.WorkoutEvent
import com.example.ftmstreadmill.workout.model.Session
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

enum class HealthConnectAvailability { AVAILABLE, NEEDS_INSTALL_OR_UPDATE, UNAVAILABLE }

sealed interface ExportResult {
    data object Success : ExportResult
    data object NotAvailable : ExportResult
    data object PermissionMissing : ExportResult
    data class Failed(val error: Exception) : ExportResult
}

/** Writes finished workouts to Health Connect so they appear in other fitness apps. */
@Singleton
class HealthConnectExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val history: HistoryRepository,
    private val settings: SettingsRepository,
    private val engine: WorkoutEngine,
    @ApplicationScope private val scope: CoroutineScope,
) {
    val availability: HealthConnectAvailability
        get() = when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectAvailability.NEEDS_INSTALL_OR_UPDATE
            else -> HealthConnectAvailability.UNAVAILABLE
        }

    private val client: HealthConnectClient?
        get() = if (availability == HealthConnectAvailability.AVAILABLE) HealthConnectClient.getOrCreate(context) else null

    suspend fun hasPermissions(): Boolean {
        val client = client ?: return false
        return client.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)
    }

    /** Exports each finished workout automatically when the user has turned that on. */
    fun startAutoExport() {
        scope.launch {
            engine.events.collect { event ->
                if (event is WorkoutEvent.Finished && event.session.id != 0L &&
                    settings.settings.first().workout.healthConnectAutoExport
                ) {
                    val result = export(event.session)
                    if (result != ExportResult.Success) Log.w(TAG, "Auto export failed: $result")
                }
            }
        }
    }

    suspend fun export(session: Session): ExportResult {
        val client = client ?: return ExportResult.NotAvailable
        return try {
            if (!hasPermissions()) return ExportResult.PermissionMissing
            client.insertRecords(buildRecords(session))
            history.markExported(session.id)
            ExportResult.Success
        } catch (e: Exception) {
            // Health Connect reports problems as RemoteException, SecurityException or IllegalArgumentException.
            Log.e(TAG, "Export failed", e)
            ExportResult.Failed(e)
        }
    }

    private fun buildRecords(session: Session): List<Record> {
        val start = Instant.ofEpochMilli(session.startedAt)
        val end = Instant.ofEpochMilli(maxOf(session.endedAt, session.startedAt + 1_000))
        val offset = ZoneId.systemDefault().rules.getOffset(start)
        val endOffset = ZoneId.systemDefault().rules.getOffset(end)
        // A stable client id makes a repeated export update the same records instead of duplicating them.
        fun metadata(kind: String) = Metadata.activelyRecorded(DEVICE, "ftms-session-${session.startedAt}-$kind", 1)

        var lastEnd = start
        val segments = session.segments.mapNotNull { result ->
            val segStart = maxOf(Instant.ofEpochMilli(result.startedAt), lastEnd)
            val segEnd = minOf(Instant.ofEpochMilli(result.endedAt), end)
            if (!segEnd.isAfter(segStart)) return@mapNotNull null
            lastEnd = segEnd
            ExerciseSegment(segStart, segEnd, ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING_TREADMILL, 0)
        }
        val records = mutableListOf<Record>(
            ExerciseSessionRecord(
                startTime = start,
                startZoneOffset = offset,
                endTime = end,
                endZoneOffset = endOffset,
                metadata = metadata("session"),
                exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
                title = session.workoutName,
                notes = null,
                segments = segments,
            ),
        )
        session.distanceMeters?.takeIf { it > 0 }?.let {
            records += DistanceRecord(start, offset, end, endOffset, Length.meters(it.toDouble()), metadata("distance"))
        }
        session.kcal?.takeIf { it > 0 }?.let {
            records += TotalCaloriesBurnedRecord(start, offset, end, endOffset, Energy.kilocalories(it.toDouble()), metadata("kcal"))
        }
        val samples = session.samples
            .filter { it.time >= session.startedAt && it.time <= end.toEpochMilli() }
            .map { SpeedRecord.Sample(Instant.ofEpochMilli(it.time), Velocity.kilometersPerHour(it.speedKmh)) }
        if (samples.isNotEmpty()) {
            records += SpeedRecord(start, offset, end, endOffset, samples, metadata("speed"))
        }
        return records
    }

    companion object {
        private const val TAG = "HealthConnectExporter"
        const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"
        private val DEVICE = Device(type = Device.TYPE_UNKNOWN, manufacturer = null, model = "Treadmill")

        val PERMISSIONS = setOf(
            HealthPermission.getWritePermission(ExerciseSessionRecord::class),
            HealthPermission.getWritePermission(DistanceRecord::class),
            HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getWritePermission(SpeedRecord::class),
        )
    }
}
