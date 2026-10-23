package com.example.vibratext.app.vibration

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.example.vibratext.data.VibrationUsage
import com.example.vibratext.playback.VibrationSink

/**
 * The real vibrator. Every waveform is started with `VibrationEffect.createWaveform` and the
 * audio-attributes usage chosen in the settings (it decides whether the system suppresses the
 * vibration in silent / Do Not Disturb mode). [cancel] calls `Vibrator.cancel()`, which stops the
 * motor immediately.
 */
class AndroidVibrationSink(
    context: Context,
    private val usage: () -> VibrationUsage,
) : VibrationSink {

    private val vibrator: Vibrator? = findVibrator(context.applicationContext)

    override fun isAvailable(): Boolean = vibrator?.hasVibrator() == true

    override fun supportsAmplitude(): Boolean = vibrator?.hasAmplitudeControl() == true

    override fun vibrate(timings: LongArray, amplitudes: IntArray) {
        val v = vibrator ?: throw IllegalStateException("This device has no vibrator")
        require(timings.size == amplitudes.size) { "timings and amplitudes must have the same length" }
        if (timings.all { it <= 0L }) return
        val effect = if (supportsAmplitude()) {
            VibrationEffect.createWaveform(timings, amplitudes, NO_REPEAT)
        } else {
            VibrationEffect.createWaveform(timings, NO_REPEAT)
        }
        val attributes = AudioAttributes.Builder()
            .setUsage(
                when (usage()) {
                    VibrationUsage.NOTIFICATION -> AudioAttributes.USAGE_NOTIFICATION
                    VibrationUsage.ACCESSIBILITY -> AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
                    VibrationUsage.ALARM -> AudioAttributes.USAGE_ALARM
                },
            )
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        @Suppress("DEPRECATION") // the AudioAttributes overload is the one that works on API 26..35
        v.vibrate(effect, attributes)
    }

    override fun cancel() {
        try {
            vibrator?.cancel()
        } catch (_: RuntimeException) {
            // Stopping must never throw into the caller; the vibrator service is gone or restarting.
        }
    }

    private companion object {
        const val NO_REPEAT = -1

        fun findVibrator(context: Context): Vibrator? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
    }
}
