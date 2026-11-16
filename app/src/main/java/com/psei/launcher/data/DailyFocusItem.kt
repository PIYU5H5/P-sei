package com.psei.launcher.data

import java.util.UUID

/**
 * Data model for a daily focus / intent item on the minimalist home screen.
 * Users can set up to 3 priority focus items for the day.
 */
data class DailyFocusItem(
    val id: String = UUID.randomUUID().toString(),
    var text: String,
    var isCompleted: Boolean = false
)
