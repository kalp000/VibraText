package com.example.vibratext.app.notification

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.PowerManager

/** Reads the few phone states the notification filter needs. None of them needs a permission. */
object DeviceState {

    /** Screen on and not locked. */
    fun deviceInUse(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val screenOn = power?.isInteractive ?: false
        val locked = keyguard?.isKeyguardLocked ?: false
        return screenOn && !locked
    }

    /** Any Do Not Disturb mode other than "allow all". */
    fun doNotDisturbActive(context: Context): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        return when (nm.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            NotificationManager.INTERRUPTION_FILTER_NONE,
            NotificationManager.INTERRUPTION_FILTER_ALARMS,
            -> true
            else -> false
        }
    }

    /**
     * A phone call or a VoIP call is in progress, or the phone is ringing. Uses the audio mode, which
     * is readable without the READ_PHONE_STATE permission.
     */
    fun inCall(context: Context): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return when (audio.mode) {
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION,
            AudioManager.MODE_RINGTONE,
            -> true
            else -> false
        }
    }
}
