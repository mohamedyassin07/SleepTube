package com.sleeptube

import android.util.Log
import java.io.File

private const val FILE_TAG = "SleepTube"

/** Deletes the file; true if it is gone afterwards. Logs a warning when it could not be deleted. */
fun File.deleteOrWarn(): Boolean {
    if (!exists()) return true
    val ok = delete()
    if (!ok) Log.w(FILE_TAG, "Could not delete file: $path")
    return ok
}

/** Deletes the file or directory tree; true if it is gone afterwards. Logs a warning otherwise. */
fun File.deleteTreeOrWarn(): Boolean {
    if (!exists()) return true
    val ok = deleteRecursively()
    if (!ok) Log.w(FILE_TAG, "Could not delete directory tree: $path")
    return ok
}

/** Makes sure this directory exists; true if it does afterwards. Logs a warning otherwise. */
fun File.ensureDir(): Boolean {
    if (isDirectory) return true
    val ok = mkdirs() || isDirectory
    if (!ok) Log.w(FILE_TAG, "Could not create directory: $path")
    return ok
}

/** 12:30 or 1:05:09 */
fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
