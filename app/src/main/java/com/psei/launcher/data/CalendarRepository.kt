package com.psei.launcher.data

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import java.util.Calendar

/**
 * Lightweight, offline loader for Google Calendar events from the local Android Calendar Provider.
 * Requires android.permission.READ_CALENDAR. Zero internet or Google Play API dependencies required.
 */
object CalendarRepository {

    fun getTodayEvents(context: Context): List<CalendarEventModel> {
        val events = mutableListOf<CalendarEventModel>()
        try {
            val startOfDay = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            val endOfDay = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
                set(Calendar.MILLISECOND, 999)
            }.timeInMillis

            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, startOfDay)
            ContentUris.appendId(builder, endOfDay)

            val projection = arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY
            )

            context.contentResolver.query(
                builder.build(),
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(CalendarContract.Instances.EVENT_ID)
                val titleIdx = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
                val beginIdx = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
                val endIdx = cursor.getColumnIndex(CalendarContract.Instances.END)
                val allDayIdx = cursor.getColumnIndex(CalendarContract.Instances.ALL_DAY)

                while (cursor.moveToNext()) {
                    val id = if (idIdx >= 0) cursor.getLong(idIdx) else 0L
                    val rawTitle = if (titleIdx >= 0) cursor.getString(titleIdx) else null
                    val title = if (rawTitle.isNullOrBlank()) "(No title)" else rawTitle
                    val begin = if (beginIdx >= 0) cursor.getLong(beginIdx) else 0L
                    val end = if (endIdx >= 0) cursor.getLong(endIdx) else 0L
                    val allDay = if (allDayIdx >= 0) cursor.getInt(allDayIdx) == 1 else false

                    events.add(
                        CalendarEventModel(
                            id = id,
                            title = title,
                            startMillis = begin,
                            endMillis = end,
                            isAllDay = allDay
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Defensive in case of permission revocation or system provider variations
        }
        return events
    }
}


