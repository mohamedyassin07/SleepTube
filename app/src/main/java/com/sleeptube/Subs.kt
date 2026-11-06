package com.sleeptube

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A followed channel. [startDate] is yyyyMMdd (yt-dlp upload_date format). */
data class Sub(
    val id: String,
    val name: String,
    val url: String,
    val startDate: String,
    val keyword: String = "",
    val lastChecked: Long = 0L,
    val lastAttempt: Long = 0L,
    val downloaded: Int = 0,
)

object Subs {
    const val CHECK_PREFIX = "sleeptube:check:"
    private const val K_LIST = "subs"
    private const val DAY = 24 * 3600_000L
    private const val RETRY_AFTER_FAIL = 3600_000L

    private lateinit var sp: SharedPreferences
    private val _version = MutableStateFlow(0)
    /** Bumped on every change so the UI can refresh. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun init(ctx: Context) {
        if (!::sp.isInitialized) sp = ctx.applicationContext.getSharedPreferences("subs", Context.MODE_PRIVATE)
    }

    // ------------------------------------------------------------------ storage

    private fun read(): MutableList<Sub> {
        val arr = try { JSONArray(sp.getString(K_LIST, "[]")) } catch (_: Exception) { JSONArray() }
        val out = ArrayList<Sub>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Sub(
                    o.optString("id"), o.optString("name"), o.optString("url"),
                    o.optString("start"), o.optString("kw"),
                    o.optLong("checked"), o.optLong("attempt"), o.optInt("dl"),
                )
            )
        }
        return out
    }

    private fun write(list: List<Sub>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name).put("url", it.url)
                    .put("start", it.startDate).put("kw", it.keyword)
                    .put("checked", it.lastChecked).put("attempt", it.lastAttempt).put("dl", it.downloaded)
            )
        }
        sp.edit().putString(K_LIST, arr.toString()).commit()
        _version.value++
    }

    @Synchronized fun all(): List<Sub> = read()

    @Synchronized fun get(id: String): Sub? = read().firstOrNull { it.id == id }

    /** Adds or replaces (by channel id) keeping counters of an existing entry. */
    @Synchronized fun upsert(s: Sub) {
        val list = read()
        val i = list.indexOfFirst { it.id == s.id }
        if (i >= 0) {
            val old = list[i]
            list[i] = s.copy(lastChecked = old.lastChecked, lastAttempt = old.lastAttempt, downloaded = old.downloaded)
        } else list.add(s)
        write(list)
    }

    @Synchronized fun update(id: String, f: (Sub) -> Sub) {
        val list = read()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        list[i] = f(list[i])
        write(list)
    }

    /** Removes the subscription only; downloaded files stay. */
    @Synchronized fun delete(id: String) {
        write(read().filter { it.id != id })
        sp.edit().remove("seen:$id").remove("rej:$id").commit()
    }

    // ids already enqueued/downloaded (never re-downloaded, even if the file is deleted)
    @Synchronized fun seen(id: String): MutableSet<String> = readSet("seen:$id")
    @Synchronized fun addSeen(id: String, ids: Collection<String>) = addTo("seen:$id", ids)
    @Synchronized fun unsee(id: String, vid: String) {
        val s = readSet("seen:$id")
        if (s.remove(vid)) writeSet("seen:$id", s)
    }

    // ids rejected for good (Shorts / too short); avoids re-fetching their metadata
    @Synchronized fun rejected(id: String): MutableSet<String> = readSet("rej:$id")
    @Synchronized fun addRejected(id: String, ids: Collection<String>) = addTo("rej:$id", ids)

    private fun readSet(key: String): MutableSet<String> {
        val arr = try { JSONArray(sp.getString(key, "[]")) } catch (_: Exception) { JSONArray() }
        val out = LinkedHashSet<String>()
        for (i in 0 until arr.length()) out.add(arr.optString(i))
        return out
    }

    private fun writeSet(key: String, set: Set<String>) {
        val arr = JSONArray()
        set.forEach { arr.put(it) }
        sp.edit().putString(key, arr.toString()).commit()
    }

    private fun addTo(key: String, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val s = readSet(key)
        if (s.addAll(ids)) writeSet(key, s)
    }

    fun markChecked(id: String, success: Boolean) {
        val now = System.currentTimeMillis()
        update(id) { if (success) it.copy(lastChecked = now, lastAttempt = now) else it.copy(lastAttempt = now) }
    }

    fun incDownloaded(id: String) = update(id) { it.copy(downloaded = it.downloaded + 1) }

    // ------------------------------------------------------------------ checks

    fun checkItem(id: String) = QueueItem(CHECK_PREFIX + id)

    /** Queues checks for subscriptions not checked in the last 24h. Returns true if any was queued. */
    fun enqueueDueChecks(ctx: Context): Boolean {
        // No (Wi-Fi) internet: skip silently; nothing is marked, so it runs next time.
        if (!Net.allowed(ctx)) return false
        val now = System.currentTimeMillis()
        var any = false
        all().forEach {
            val due = now - it.lastChecked >= DAY && now - it.lastAttempt >= RETRY_AFTER_FAIL
            if (due && Prefs.enqueue(checkItem(it.id))) any = true
        }
        return any
    }

    fun enqueueCheck(id: String): Boolean = Prefs.enqueue(checkItem(id))

    fun enqueueAllChecks(): Boolean {
        var any = false
        all().forEach { if (Prefs.enqueue(checkItem(it.id))) any = true }
        return any
    }

    // ------------------------------------------------------------------ helpers

    private val CHANNEL_PATH = Regex("""^/(@[^/]+|channel/[^/]+|c/[^/]+|user/[^/]+)(/[^/]*)?/?$""")

    /** True for youtube channel URLs (/@handle, /channel/, /c/, /user/) that aren't a video. */
    fun isChannelUrl(url: String): Boolean {
        val u = try { Uri.parse(url) } catch (_: Exception) { return false }
        val host = u.host?.lowercase() ?: return false
        if (!host.endsWith("youtube.com")) return false
        if (u.getQueryParameter("v") != null || u.getQueryParameter("list") != null) return false
        return CHANNEL_PATH.matches(u.path ?: "")
    }

    /** Channel URL pointing at its "Videos" tab (no Shorts, no streams). */
    fun videosTabUrl(url: String): String {
        val u = Uri.parse(url.trim())
        val m = CHANNEL_PATH.find(u.path ?: "")
        val base = if (m != null) "https://www.youtube.com/" + m.groupValues[1] else url.trim().trimEnd('/')
        return "$base/videos"
    }

    data class Resolved(val id: String, val name: String)

    /** Resolves channel id and name with yt-dlp. Blocking; call on a background thread. */
    fun resolve(ctx: Context, url: String): Resolved {
        Ytdl.init(ctx)
        val req = YoutubeDLRequest(videosTabUrl(url))
        req.addOption("--flat-playlist")
        req.addOption("--playlist-end", "1")
        req.addOption("-J")
        val out = YoutubeDL.getInstance().execute(req).out
        val o = JSONObject(out.substring(out.indexOf('{')))
        val id = o.optString("channel_id").ifEmpty { o.optString("id") }
        if (!id.startsWith("UC")) throw IllegalStateException("مش لاقي القناة")
        val name = o.optString("channel").ifEmpty { o.optString("uploader") }
            .ifEmpty { o.optString("title").removeSuffix(" - Videos") }
            .ifEmpty { id }
        return Resolved(id, name)
    }

    fun canonicalUrl(id: String) = "https://www.youtube.com/channel/$id/videos"

    fun todayYmd(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    fun prettyYmd(ymd: String): String =
        if (ymd.length == 8) "${ymd.substring(0, 4)}-${ymd.substring(4, 6)}-${ymd.substring(6, 8)}" else ymd

    fun prettyTime(ms: Long): String =
        if (ms <= 0) "لسه" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))
}
