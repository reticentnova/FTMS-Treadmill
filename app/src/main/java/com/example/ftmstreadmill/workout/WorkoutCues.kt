package com.example.ftmstreadmill.workout

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.VibrationEffect
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.ftmstreadmill.R
import com.example.ftmstreadmill.data.Settings
import com.example.ftmstreadmill.data.SettingsRepository
import com.example.ftmstreadmill.di.ApplicationScope
import com.example.ftmstreadmill.workout.model.PlannedSegment
import com.example.ftmstreadmill.workout.model.SegmentKind
import com.example.ftmstreadmill.workout.model.SpeedUnit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns [WorkoutEngine] events into beeps, spoken announcements and vibration so a workout can be
 * followed without looking at the phone. Runs for the life of the process, so it works with the
 * screen off.
 */
@Singleton
class WorkoutCues @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: WorkoutEngine,
    settingsRepository: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val settings = settingsRepository.settings.stateIn(scope, SharingStarted.Eagerly, Settings())
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    private var tones: ToneGenerator? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch { engine.events.collect(::onEvent) }
    }

    private fun onEvent(event: WorkoutEvent) {
        val prefs = settings.value.workout
        val imperial = settings.value.useImperialUnits
        when (event) {
            is WorkoutEvent.Countdown -> {
                if (prefs.cueSound) beep(short = true)
                if (prefs.cueVibrate) vibrate(longArrayOf(0, 60))
            }
            is WorkoutEvent.SegmentStarted -> {
                if (prefs.cueSound) beep(short = false)
                if (prefs.cueVibrate) vibrate(longArrayOf(0, 250, 120, 250))
                if (prefs.cueVoice) speak(describe(event.segment, imperial, first = event.isFirst))
            }
            WorkoutEvent.Halfway -> if (prefs.cueVoice) speak(context.getString(R.string.cue_halfway))
            is WorkoutEvent.Paused -> if (prefs.cueVoice) speak(context.getString(R.string.cue_paused))
            WorkoutEvent.Resumed -> if (prefs.cueVoice) speak(context.getString(R.string.cue_resumed))
            is WorkoutEvent.Finished -> {
                if (prefs.cueVibrate) vibrate(longArrayOf(0, 400, 150, 400))
                if (prefs.cueVoice) {
                    speak(context.getString(if (event.session.completed) R.string.cue_complete else R.string.cue_ended))
                }
            }
        }
    }

    private fun describe(segment: PlannedSegment, imperial: Boolean, first: Boolean): String {
        val unit = if (imperial) SpeedUnit.MPH else SpeedUnit.KMH
        val speed = "%.1f".format(Locale.getDefault(), unit.fromKmh(segment.speedKmh))
        val incline = "%.1f".format(Locale.getDefault(), segment.inclinePercent).removeSuffix(".0").removeSuffix(",0")
        val unitWords = context.getString(if (imperial) R.string.cue_mph else R.string.cue_kmh)
        val name = segment.name ?: context.getString(
            when (segment.kind) {
                SegmentKind.WARMUP -> R.string.segment_warmup
                SegmentKind.COOLDOWN -> R.string.segment_cooldown
                SegmentKind.INTERVAL -> R.string.segment_interval
            },
        )
        val minutes = segment.durationSec / 60
        val seconds = segment.durationSec % 60
        val length = when {
            minutes == 0 -> context.resources.getQuantityString(R.plurals.cue_seconds, seconds, seconds)
            seconds == 0 -> context.resources.getQuantityString(R.plurals.cue_minutes, minutes, minutes)
            else -> context.resources.getQuantityString(R.plurals.cue_minutes, minutes, minutes) + " " +
                context.resources.getQuantityString(R.plurals.cue_seconds, seconds, seconds)
        }
        val template = if (first) R.string.cue_starting else R.string.cue_segment
        return context.getString(template, name, speed, unitWords, incline, length)
    }

    private fun beep(short: Boolean) {
        try {
            val generator = tones ?: ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME).also { tones = it }
            generator.startTone(
                if (short) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_BEEP2,
                if (short) 150 else 400,
            )
        } catch (e: RuntimeException) {
            // ToneGenerator throws if the audio system is busy; a missed beep isn't worth crashing over.
            Log.w(TAG, "Beep failed", e)
        }
    }

    private fun vibrate(pattern: LongArray) {
        vibrator?.takeIf { it.hasVibrator() }?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun speak(text: String) {
        val engine = tts
        if (engine == null) {
            tts = TextToSpeech(context) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.setAudioAttributes(attributes)
                    tts?.setOnUtteranceProgressListener(focusReleaser)
                    say(text)
                }
            }
            return
        }
        if (ttsReady) say(text)
    }

    private fun say(text: String) {
        audioManager?.requestAudioFocus(focusRequest)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), text.hashCode().toString())
    }

    private val focusReleaser = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) {
            audioManager?.abandonAudioFocusRequest(focusRequest)
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            audioManager?.abandonAudioFocusRequest(focusRequest)
        }
    }

    private companion object {
        const val TAG = "WorkoutCues"
        const val TONE_VOLUME = 80
    }
}
