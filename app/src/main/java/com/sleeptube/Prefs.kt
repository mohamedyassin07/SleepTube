package com.sleeptube

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray

object Prefs {
    private lateinit var sp: SharedPreferences

    private const val K_FOLDER = "folder_uri"
    private const val K_TIMER = "timer_minutes"
    private const val K_LAST_UPDATE = "last_ytdl_update"
    private const val K_QUEUE = "download_queue"
    private const val K_PENDING = "pending_share_url"

    val TIMER_CHOICES = listOf(15, 30, 45, 60, 90, 120)

    fun init(ctx: Context) {
        if (!::sp.isInitialized) {
            sp = ctx.applicationContext.getSharedPreferences("sleeptube", Context.MODE_PRIVATE)
        }
    }

    var folderUri: Uri?
        get() = sp.getString(K_FOLDER, null)?.let { Uri.parse(it) }
        set(v) { sp.edit().putString(K_FOLDER, v?.toString()).apply() }

    var timerMinutes: Int
        get() = sp.getInt(K_TIMER, 30)
        set(v) { sp.edit().putInt(K_TIMER, v).apply() }

    var lastUpdate: Long
        get() = sp.getLong(K_LAST_UPDATE, 0L)
        set(v) { sp.edit().putLong(K_LAST_UPDATE, v).apply() }

    /** URL that arrived via share while no folder was selected. */
    var pendingUrl: String?
        get() = sp.getString(K_PENDING, null)
        set(v) { sp.edit().putString(K_PENDING, v).apply() }

    // ---- per-file playback state, keyed by document uri string ----
    private fun posKey(uri: String) = "pos:$uri"
    private fun durKey(uri: String) = "dur:$uri"
    private fun finKey(uri: String) = "fin:$uri"

    fun position(uri: String): Long = sp.getLong(posKey(uri), 0L)
    fun duration(uri: String): Long = sp.getLong(durKey(uri), 0L)
    fun finished(uri: String): Boolean = sp.getBoolean(finKey(uri), false)

    /** Position to start from: 0 for finished items. */
    fun startPosition(uri: String): Long = if (finished(uri)) 0L else position(uri)

    fun savePosition(uri: String, positionMs: Long, durationMs: Long) {
        val knownDur = if (durationMs > 0) durationMs else duration(uri)
        if (knownDur > 0 && positionMs >= knownDur - 1500) {
            // Stopped at the very end: treat as finished so it doesn't resume at the end.
            markFinished(uri, knownDur)
            return
        }
        val e = sp.edit()
        e.putLong(posKey(uri), positionMs.coerceAtLeast(0L))
        if (durationMs > 0) e.putLong(durKey(uri), durationMs)
        // Re-watching a finished item and stopping midway makes it "in progress" again.
        if (positionMs > 0) e.putBoolean(finKey(uri), false)
        e.apply()
    }

    fun markFinished(uri: String, durationMs: Long) {
        val e = sp.edit()
        e.putBoolean(finKey(uri), true)
        e.putLong(posKey(uri), 0L)
        if (durationMs > 0) e.putLong(durKey(uri), durationMs)
        e.apply()
    }

    fun forget(uri: String) {
        sp.edit().remove(posKey(uri)).remove(durKey(uri)).remove(finKey(uri)).apply()
    }

    // ---- persisted download queue ----
    private fun readQueue(): MutableList<String> {
        val arr = try { JSONArray(sp.getString(K_QUEUE, "[]")) } catch (e: Exception) { JSONArray() }
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) out.add(arr.optString(i))
        return out
    }

    private fun writeQueue(list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        sp.edit().putString(K_QUEUE, arr.toString()).commit()
    }

    @Synchronized fun queue(): List<String> = readQueue()

    @Synchronized fun queueSize(): Int = readQueue().size

    @Synchronized fun queueHead(): String? = readQueue().firstOrNull()

    /** Adds url if not already queued. Returns true if added. */
    @Synchronized fun enqueue(url: String): Boolean {
        val q = readQueue()
        if (q.contains(url)) return false
        q.add(url)
        writeQueue(q)
        return true
    }

    @Synchronized fun removeHead(expected: String) {
        val q = readQueue()
        if (q.isNotEmpty() && q[0] == expected) q.removeAt(0) else q.remove(expected)
        writeQueue(q)
    }

    /** Replaces url (playlist) with its expanded list, in place. */
    @Synchronized fun replace(url: String, items: List<String>) {
        val q = readQueue()
        val idx = q.indexOf(url)
        val fresh = items.distinct().filter { it !in q }
        if (idx >= 0) {
            q.removeAt(idx)
            q.addAll(idx, fresh)
        } else {
            q.addAll(0, fresh)
        }
        writeQueue(q)
    }

    @Synchronized fun clearQueue() = writeQueue(emptyList())
}
