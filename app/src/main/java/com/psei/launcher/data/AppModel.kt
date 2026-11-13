package com.psei.launcher.data

import android.content.ComponentName
import android.os.Process
import android.os.UserHandle

/**
 * Lightweight in-memory representation of an installed launcher application.
 * Icons are intentionally omitted to keep memory usage minimal and speeds instant.
 */
data class AppModel(
    val packageName: String,
    val activityName: String,
    val userHandle: UserHandle = Process.myUserHandle(),
    val originalLabel: String,
    var customLabel: String? = null
) {
    /**
     * Effective display name taking user renames into account.
     */
    val label: String
        get() = customLabel?.takeIf { it.isNotBlank() } ?: originalLabel

    /**
     * Unique stable key combining package and activity name.
     */
    val componentKey: String
        get() = "$packageName/$activityName"

    /**
     * Stable ID for RecyclerView recycling optimizations.
     */
    val stableId: Long
        get() = componentKey.hashCode().toLong()

    /**
     * Target component name for explicit intent launching.
     */
    val componentName: ComponentName
        get() = ComponentName(packageName, activityName)
}


