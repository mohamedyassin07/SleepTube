package com.sleeptube

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

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
    fun startPosition(uri: String): Long {
        if (finished(uri)) return 0L
        val pos = position(uri)
        val dur = duration(uri)
        // Never resume at (or past) the very end: that would end immediately and skip on.
        if (dur > 0 && pos >= dur - 3000) return 0L
        return pos
    }

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
        sp.edit().remove(posKey(uri)).remove(durKey(uri)).remove(finKey(uri)).remove(addedKey(uri)).apply()
    }

    // ---- stable "added to the library" time per file (list order) ----
    private fun addedKey(uri: String) = "added:$uri"

    /** Stored added time, or 0 if none yet. */
    fun added(uri: String): Long = sp.getLong(addedKey(uri), 0L)

    fun setAdded(uri: String, time: Long) { sp.edit().putLong(addedKey(uri), time).apply() }

    /** Persists added times for several files in one write. */
    fun setAddedAll(times: Map<String, Long>) {
        if (times.isEmpty()) return
        val e = sp.edit()
        times.forEach { (k, v) -> e.putLong(addedKey(k), v) }
        e.apply()
    }

    // ---- persisted download queue ----
    // Format: JSON array of {"u": url, "d": subfolder document id or absent, "i": playlist index}.
    // Older builds stored plain URL strings; those are read as root-folder entries.
    private fun readQueue(): MutableList<QueueItem> {
        val arr = try { JSONArray(sp.getString(K_QUEUE, "[]")) } catch (e: Exception) { JSONArray() }
        val out = ArrayList<QueueItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.opt(i)
            when (o) {
                is JSONObject -> {
                    val u = o.optString("u")
                    if (u.isNotEmpty()) out.add(
                        QueueItem(
                            u, o.optString("d").ifEmpty { null }, o.optInt("i", 0), o.optInt("n", 0),
                            o.optString("p").ifEmpty { null }, o.optString("s").ifEmpty { null },
                        )
                    )
                }
                is String -> if (o.isNotEmpty()) out.add(QueueItem(o))
            }
        }
        return out
    }

    /** Returns false if the write did not reach disk. */
    private fun writeQueue(list: List<QueueItem>): Boolean {
        val arr = JSONArray()
        list.forEach {
            val o = JSONObject().put("u", it.url)
            if (it.dir != null) o.put("d", it.dir)
            if (it.index > 0) o.put("i", it.index)
            if (it.total > 0) o.put("n", it.total)
            if (it.prefix != null) o.put("p", it.prefix)
            if (it.sub != null) o.put("s", it.sub)
            arr.put(o)
        }
        return sp.edit().putString(K_QUEUE, arr.toString()).commit()
    }

    @Synchronized fun queue(): List<QueueItem> = readQueue()

    @Synchronized fun queueSize(): Int = readQueue().size

    @Synchronized fun queueHead(): QueueItem? = readQueue().firstOrNull()

    /** Adds the item if the same url/target is not already queued. Returns true if added. */
    @Synchronized fun enqueue(item: QueueItem): Boolean {
        val q = readQueue()
        if (q.any { it.url == item.url && it.dir == item.dir }) return false
        q.add(item)
        return writeQueue(q)
    }

    /**
     * Removes [expected] (normally the head). Returns true if it is no longer in the queue
     * afterwards, false if the queue could not be advanced (write failed).
     */
    @Synchronized fun removeHead(expected: QueueItem): Boolean {
        val q = readQueue()
        val idx = q.indexOf(expected)
        if (idx < 0) return true
        q.removeAt(idx)
        writeQueue(q)
        return expected !in readQueue()
    }

    /** Replaces [entry] (a playlist) with its expanded items, in place. */
    @Synchronized fun replace(entry: QueueItem, items: List<QueueItem>): Boolean {
        val q = readQueue()
        val idx = q.indexOf(entry)
        val fresh = items.distinct().filter { n -> q.none { it.url == n.url && it.dir == n.dir } }
        if (idx >= 0) {
            q.removeAt(idx)
            q.addAll(idx, fresh)
        } else {
            q.addAll(0, fresh)
        }
        return writeQueue(q)
    }

    @Synchronized fun clearQueue() { writeQueue(emptyList()) }

    /**
     * All downloads (shared links, playlists, subscriptions) and self-updates only on Wi-Fi /
     * unmetered networks. Same key as the earlier subscriptions-only switch, so the value carries over.
     */
    var wifiOnly: Boolean
        get() = sp.getBoolean("subs_wifi_only", true)
        set(v) { sp.edit().putBoolean("subs_wifi_only", v).apply() }

    // ---- in-app updater ----
    var lastAppUpdateCheck: Long
        get() = sp.getLong("last_app_update_check", 0L)
        set(v) { sp.edit().putLong("last_app_update_check", v).apply() }
}

/** One download job. [dir] = document id of the target subfolder (null = chosen folder root). */
data class QueueItem(
    val url: String,
    val dir: String? = null,
    val index: Int = 0,
    val total: Int = 0,
    /** File name prefix, e.g. upload date "2026-11-01" for subscription downloads. */
    val prefix: String? = null,
    /** Subscription (channel id) this download came from. */
    val sub: String? = null,
) {
    val isSubCheck: Boolean get() = url.startsWith(Subs.CHECK_PREFIX)
}
