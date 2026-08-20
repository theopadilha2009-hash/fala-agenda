package com.theopadilha.falaagenda.reminders

import android.content.Context
import android.content.Intent
import com.theopadilha.falaagenda.MainActivity
import java.nio.ByteBuffer
import java.security.MessageDigest

object AlarmIds {
    const val ACTION_FIRE = "fire"
    const val ACTION_COMPLETE = "complete"
    const val ACTION_SNOOZE = "snooze"
    const val ACTION_OPEN = "open"
    const val ACTION_OPEN_OCCURRENCE = "com.theopadilha.falaagenda.OPEN_OCCURRENCE"
    const val EXTRA_OCCURRENCE_ID = "occurrence_id"
    const val EXTRA_SERIES_ID = "series_id"
    const val EXTRA_ACTION = "action"

    fun openIntent(context: Context, occurrenceId: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_OCCURRENCE
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OCCURRENCE_ID, occurrenceId)
        }

    fun requestCode(occurrenceId: String, action: String): Int {
        val lane = when (action) {
            ACTION_FIRE -> 1
            ACTION_COMPLETE -> 2
            ACTION_SNOOZE -> 3
            ACTION_OPEN -> 4
            "notif" -> 5
            else -> 6
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(occurrenceId.toByteArray(Charsets.UTF_8))
        val base = ByteBuffer.wrap(digest, 0, 4).int and 0x0FFFFFFF
        return (lane shl 28) or base
    }
}
