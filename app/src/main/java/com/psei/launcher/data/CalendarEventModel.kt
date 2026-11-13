package com.psei.launcher.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Representation of a single Google Calendar event.
 */
data class CalendarEventModel(
    val id: Long,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val isAllDay: Boolean
) {
    fun getFormattedTime(): String {
        return if (isAllDay) {
            "All day"
        } else {
            val format = SimpleDateFormat("h:mm a", Locale.getDefault())
            format.format(Date(startMillis))
        }
    }
}


