package com.theopadilha.falaagenda.reminders

import java.security.MessageDigest
import java.nio.ByteBuffer

object AlarmIds {
    const val ACTION_FIRE = "fire"
    const val ACTION_COMPLETE = "complete"
    const val ACTION_SNOOZE = "snooze"
    const val EXTRA_OCCURRENCE_ID = "occurrence_id"
    const val EXTRA_SERIES_ID = "series_id"
    const val EXTRA_ACTION = "action"

    fun requestCode(occurrenceId: String, action: String): Int {
        val lane = when (action) {
            ACTION_FIRE -> 1
            ACTION_COMPLETE -> 2
            ACTION_SNOOZE -> 3
            "open" -> 4
            "notif" -> 5
            else -> 6
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(occurrenceId.toByteArray(Charsets.UTF_8))
        val base = ByteBuffer.wrap(digest, 0, 4).int and 0x0FFFFFFF
        return (lane shl 28) or base
    }
}
