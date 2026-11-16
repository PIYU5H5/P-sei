package com.psei.launcher.data

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import java.util.Calendar
import java.util.TimeZone

/**
 * Lightweight, offline loader for Google Calendar events from the local Android Calendar Provider.
 * Requires android.permission.READ_CALENDAR. Zero internet or Google Play API dependencies required.
 */
object CalendarRepository {

    fun getTodayEvents(context: Context): List<CalendarEventModel> {
        val events = mutableListOf<CalendarEventModel>()
        try {
            val now = System.currentTimeMillis()

            // Local device calendar date components
            val localCal = Calendar.getInstance()
            val todayYear = localCal.get(Calendar.YEAR)
            val todayMonth = localCal.get(Calendar.MONTH)
            val todayDay = localCal.get(Calendar.DAY_OF_MONTH)

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
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.STATUS,
                CalendarContract.Instances.SELF_ATTENDEE_STATUS
            )

            val utcTimeZone = TimeZone.getTimeZone("UTC")

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
                val statusIdx = cursor.getColumnIndex(CalendarContract.Instances.STATUS)
                val attendeeStatusIdx = cursor.getColumnIndex(CalendarContract.Instances.SELF_ATTENDEE_STATUS)

                while (cursor.moveToNext()) {
                    // Filter out cancelled events
                    val status = if (statusIdx >= 0) cursor.getInt(statusIdx) else 0
                    if (status == CalendarContract.Instances.STATUS_CANCELED) continue

                    // Filter out declined invitations (2 = ATTENDEE_STATUS_DECLINED)
                    val attendeeStatus = if (attendeeStatusIdx >= 0) cursor.getInt(attendeeStatusIdx) else 0
                    if (attendeeStatus == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue

                    val id = if (idIdx >= 0) cursor.getLong(idIdx) else 0L
                    val rawTitle = if (titleIdx >= 0) cursor.getString(titleIdx) else null
                    val title = if (rawTitle.isNullOrBlank()) "(No title)" else rawTitle
                    val begin = if (beginIdx >= 0) cursor.getLong(beginIdx) else 0L
                    val end = if (endIdx >= 0) cursor.getLong(endIdx) else 0L
                    val allDay = if (allDayIdx >= 0) cursor.getInt(allDayIdx) == 1 else false

                    if (allDay) {
                        // In Android Calendar, all-day events have begin/end stored as UTC midnight.
                        // We must verify the actual UTC date matches today's date so yesterday's
                        // or previous all-day events that ended at 05:30 AM IST are rejected.
                        val utcCalStart = Calendar.getInstance(utcTimeZone).apply { timeInMillis = begin }
                        val startYear = utcCalStart.get(Calendar.YEAR)
                        val startMonth = utcCalStart.get(Calendar.MONTH)
                        val startDay = utcCalStart.get(Calendar.DAY_OF_MONTH)

                        val utcCalEnd = Calendar.getInstance(utcTimeZone).apply {
                            timeInMillis = maxOf(begin, end - 1)
                        }
                        val endYear = utcCalEnd.get(Calendar.YEAR)
                        val endMonth = utcCalEnd.get(Calendar.MONTH)
                        val endDay = utcCalEnd.get(Calendar.DAY_OF_MONTH)

                        val isToday = isDateInRange(
                            todayYear, todayMonth, todayDay,
                            startYear, startMonth, startDay,
                            endYear, endMonth, endDay
                        )

                        if (!isToday) {
                            // Discard past or future all-day events (e.g. yesterday's holiday/birthday)
                            continue
                        }
                    } else {
                        // Timed events:
                        // 1. Must overlap with today
                        if (begin > endOfDay || end < startOfDay) {
                            continue
                        }

                        // 2. Discard events that have already finished (previous events)
                        // Allow a small 5-minute buffer so an event doesn't disappear abruptly
                        val pastBuffer = 5 * 60_000L
                        if (end < (now - pastBuffer)) {
                            continue
                        }
                    }

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

        // Deduplicate identical events across multiple Google accounts syncing the same calendar
        val distinctEvents = events.distinctBy {
            "${it.title.trim().lowercase()}_${it.startMillis}_${it.endMillis}_${it.isAllDay}"
        }.toMutableList()

        // Sort: All-day events first, then chronological by start time
        distinctEvents.sortWith(compareBy({ !it.isAllDay }, { it.startMillis }))

        return distinctEvents
    }

    private fun isDateInRange(
        year: Int, month: Int, day: Int,
        startYear: Int, startMonth: Int, startDay: Int,
        endYear: Int, endMonth: Int, endDay: Int
    ): Boolean {
        val target = year * 10000 + month * 100 + day
        val start = startYear * 10000 + startMonth * 100 + startDay
        val end = endYear * 10000 + endMonth * 100 + endDay
        return target in start..end
    }
}
