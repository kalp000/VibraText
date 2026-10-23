package com.example.vibratext.app.vibration

import android.content.Context
import android.os.PowerManager

/**
 * A partial wake lock held only while something is playing, so the batches of a long message are
 * handed to the vibrator on time even if the screen is off. It always has a time limit, so a bug
 * can never keep the phone awake.
 */
class PlaybackWakeLock(context: Context) {
    private val lock: PowerManager.WakeLock? = try {
        (context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VibraText:playback")
            .also { it.setReferenceCounted(false) }
    } catch (_: RuntimeException) {
        null
    }

    fun acquire() {
        try {
            lock?.acquire(MAX_HOLD_MS)
        } catch (_: RuntimeException) {
            // Without the lock playback still works; it is only less robust with the screen off.
        }
    }

    fun release() {
        try {
            if (lock?.isHeld == true) lock.release()
        } catch (_: RuntimeException) {
        }
    }

    private companion object {
        const val MAX_HOLD_MS = 10 * 60 * 1000L
    }
}
