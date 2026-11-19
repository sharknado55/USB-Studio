package com.example.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    val tb = gb * 1024.0
    return when {
        bytes >= tb -> String.format(Locale.US, "%.2f TB", bytes / tb)
        bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
        bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.US, "%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}

fun formatTimestamp(epochMs: Long): String {
    if (epochMs <= 0L) return "—"
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(epochMs))
}
