package com.example.vibratext.app.ui.components

import java.text.DateFormat
import java.util.Date
import java.util.Locale

object Format {
    /** 3450 -> "3.5 s", 83000 -> "1 min 23 s". */
    fun duration(ms: Long): String {
        if (ms < 60_000) return String.format(Locale.US, "%.1f s", ms / 1000.0)
        val totalSeconds = (ms + 500) / 1000
        return "${totalSeconds / 60} min ${totalSeconds % 60} s"
    }

    fun dateTime(ms: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

    fun time(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

    fun multiplier(value: Double): String = String.format(Locale.US, "%.2f×", value)
}
