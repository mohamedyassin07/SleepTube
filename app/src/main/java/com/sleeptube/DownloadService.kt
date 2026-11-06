package com.sleeptube

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

data class DlState(
    val active: Boolean = false,
    val title: String? = null,
    val progress: Float = 0f,
    val etaSec: Long = -1,
    val queued: Int = 0,
)

class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loopRunning = false // main thread only
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile private var currentProcessId: String? = null
    @Volatile private var cancelCurrent = false
    @Volatile private var stopRequested = false
    private var startRequestedWhileRunning = false // main thread only
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastNotifyAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must always call startForeground promptly after startForegroundService().
        goForeground(buildProgressNotification(_state.value.title ?: "بيجهز التحميل…", -1f, -1, Prefs.queueSize()))
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelRunning()
                // A stale cancel (e.g. old notification after process death) must not start work.
                if (!loopRunning) { finish(); return START_NOT_STICKY }
            }
            ACTION_CANCEL_ALL -> {
                Prefs.clearQueue()
                cancelRunning()
            }
            else -> if (loopRunning) startRequestedWhileRunning = true
        }
        ensureLoop()
        return START_NOT_STICKY
    }

    private fun cancelRunning() {
        cancelCurrent = true
        currentProcessId?.let {
            try { YoutubeDL.getInstance().destroyProcessById(it) } catch (_: Throwable) {}
        }
    }

    private fun goForeground(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_PROGRESS, n, type)
    }

    /** Main thread. Starts the worker loop unless it is already running. */
    private fun ensureLoop() {
        if (loopRunning || stopRequested) return
        if (Net.nextEligible(this) == null) {
            // Empty, or only items waiting for (Wi-Fi) internet: they stay queued for later.
            finish()
            return
        }
        loopRunning = true
        startRequestedWhileRunning = false
        scope.launch {
            val halted = withContext(Dispatchers.IO) {
                try { runQueue() } finally { releaseLocks() }
            }
            // Back on main thread: onStartCommand also runs here, so no enqueue can slip between
            // this check and stopSelf().
            loopRunning = false
            val newRequest = startRequestedWhileRunning
            startRequestedWhileRunning = false
            // After a halt (init failure, repeated failures, queue write failure) only a new
            // enqueue may restart the loop; otherwise it could spin forever holding locks.
            if (!stopRequested && (!halted || newRequest) &&
                Net.nextEligible(this@DownloadService) != null && VideoRepo.hasFolderAccess(this@DownloadService)
            ) {
                ensureLoop()
            } else {
                finish()
            }
        }
    }

    private fun finish() {
        _state.value = DlState(queued = Prefs.queueSize())
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** (Re)acquires the locks for one URL, each bounded by [LOCK_TIMEOUT_MS]. */
    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepTube:download").apply {
            setReferenceCounted(false)
            wakeLock = this
        }
        wl.acquire(LOCK_TIMEOUT_MS) // non reference-counted: re-acquiring just restarts the timeout
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wf = wifiLock ?: wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "SleepTube:download").apply {
            setReferenceCounted(false)
            wifiLock = this
        }
        if (!wf.isHeld) wf.acquire()
        // WifiLock has no timeout API: release it ourselves when the per-URL budget runs out.
        mainHandler.removeCallbacks(lockTimeout)
        mainHandler.postDelayed(lockTimeout, LOCK_TIMEOUT_MS)
    }

    private val lockTimeout = Runnable { releaseLocks() }

    private fun releaseLocks() {
        mainHandler.removeCallbacks(lockTimeout)
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Throwable) {}
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------ worker (IO thread)

    /** Processes the queue. Returns true if it stopped early (halted), false if the queue drained. */
    private fun runQueue(): Boolean {
        acquireLocks()
        try {
            Ytdl.init(this)
        } catch (e: Throwable) {
            notifyResult("فشل تجهيز yt-dlp", e.message ?: "")
            return true
        }
        Ytdl.updateIfStale(this, 24 * 3600_000L)
        var failures = 0
        while (!stopRequested) {
            // First item allowed on this connection; subscription items wait for Wi-Fi if
            // required, everything waits (stays queued) when there is no internet.
            val item = Net.nextEligible(this) ?: return false
            if (!VideoRepo.hasFolderAccess(this)) {
                notifyResult("اختار فولدر التحميل", "افتح التطبيق واختار الفولدر")
                return true
            }
            acquireLocks()
            cancelCurrent = false
            val ok = try {
                processItem(item)
            } catch (e: Throwable) {
                notifyResult("فشل التحميل", e.message?.take(200) ?: item.url)
                false
            }
            if (!ok && !cancelCurrent && !Net.eligible(this, item)) {
                // Lost the (Wi-Fi) connection mid-way: keep the item queued, don't count a failure.
                // The loop moves on to items still allowed, or ends and resumes on reconnect.
                continue
            }
            if (!ok && item.sub != null) {
                // Let the next subscription check try this video again.
                videoIdOf(item.url)?.let { Subs.unsee(item.sub, it) }
            }
            if (!Prefs.removeHead(item)) {
                notifyResult("فشل التحميل", "مقدرتش أحدث طابور التحميل")
                return true
            }
            if (ok) {
                failures = 0
            } else if (++failures >= MAX_CONSECUTIVE_FAILURES) {
                notifyResult("وقف التحميل", "$failures محاولات فشلت ورا بعض. هيكمل لما تضيف لينك جديد أو تفتح التطبيق.")
                return true
            }
        }
        return true
    }

    /** Returns false on failure; true on success, skip (already exists) or cancel. */
    private fun processItem(item: QueueItem): Boolean {
        val url = item.url
        if (item.isSubCheck) return checkSubscription(item)
        if (item.index == 0 && isPlaylistUrl(url)) return expandPlaylist(item)
        val vid = videoIdOf(url)
        if (vid != null && VideoRepo.existsId(this, vid, item.dir)) return true

        updateState(DlState(true, vid ?: url, 0f, -1, Prefs.queueSize()), force = true)
        val tmp = File(cacheDir, "dl/" + SystemClock.elapsedRealtime()).apply {
            deleteRecursively(); mkdirs()
        }
        try {
            var ok = false
            var lastError: Throwable? = null
            for (attempt in 0..1) {
                try {
                    runDownload(url, tmp)
                    ok = true
                    break
                } catch (e: YoutubeDL.CanceledException) {
                    return true
                } catch (e: Throwable) {
                    if (cancelCurrent) return true
                    lastError = e
                    if (attempt == 0 && System.currentTimeMillis() - Prefs.lastUpdate > 3600_000L) {
                        updateState(_state.value.copy(title = "بيحدث yt-dlp…"), force = true)
                        if (!Ytdl.updateIfStale(this, 3600_000L)) break
                        tmp.listFiles()?.forEach { it.deleteRecursively() }
                    } else break
                }
            }
            if (cancelCurrent) return true
            if (!ok) {
                notifyResult("فشل التحميل", shortError(lastError) ?: url)
                return false
            }
            val file = tmp.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                ?.maxByOrNull { it.length() }
                ?: run {
                    notifyResult("فشل التحميل", "مفيش ملف اتحمل")
                    return false
                }
            val id = VideoRepo.idOf(file.name)
            if (id != null && VideoRepo.existsId(this, id, item.dir)) return true
            val title = VideoRepo.titleOf(file.name)
            val displayName = if (item.prefix != null) {
                item.prefix + " - " + file.name
            } else if (item.dir != null && item.index > 0) {
                val width = maxOf(3, item.total.toString().length)
                item.index.toString().padStart(width, '0') + " - " + file.name
            } else file.name
            updateState(_state.value.copy(title = title, progress = 100f), force = true)
            VideoRepo.saveToFolder(this, file, item.dir, displayName)
            item.sub?.let { Subs.incDownloaded(it) }
            _changed.tryEmit(Unit)
            notifyResult("خلص التحميل", title)
            return true
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun shortError(e: Throwable?): String? {
        val msg = e?.message ?: return null
        val line = msg.lines().lastOrNull { it.contains("ERROR") } ?: msg.lines().firstOrNull { it.isNotBlank() }
        return line?.take(200)
    }

    private fun runDownload(url: String, tmp: File) {
        val req = YoutubeDLRequest(url)
        req.addOption("-f", FORMAT)
        req.addOption("--no-playlist")
        req.addOption("--no-mtime")
        req.addOption("--windows-filenames")
        req.addOption("--retries", "10")
        req.addOption("--fragment-retries", "10")
        req.addOption("--newline")
        req.addOption("-o", tmp.absolutePath + "/%(title).120B [%(id)s].%(ext)s")
        val pid = "dl-" + counter.incrementAndGet()
        currentProcessId = pid
        try {
            if (cancelCurrent) return
            YoutubeDL.getInstance().execute(req, pid) { progress, eta, line ->
                var title = _state.value.title
                val idx = line.indexOf("Destination:")
                if (idx >= 0) {
                    val path = line.substring(idx + "Destination:".length).trim()
                    title = VideoRepo.titleOf(File(path).name)
                }
                updateState(DlState(true, title, progress, eta, Prefs.queueSize()), force = idx >= 0)
            }
        } finally {
            currentProcessId = null
        }
    }

    /** Runs yt-dlp and parses its JSON output. Throws on failure / CanceledException on cancel. */
    private fun execJson(req: YoutubeDLRequest, tag: String): JSONObject {
        val pid = tag + "-" + counter.incrementAndGet()
        currentProcessId = pid
        try {
            if (cancelCurrent) throw IllegalStateException("canceled")
            val out = YoutubeDL.getInstance().execute(req, pid).out
            return JSONObject(out.substring(out.indexOf('{')))
        } finally {
            currentProcessId = null
        }
    }

    private fun isLiveish(liveStatus: String) =
        liveStatus == "is_live" || liveStatus == "is_upcoming" || liveStatus == "post_live"

    /**
     * Looks for new uploads of a followed channel and queues them in place of the check item.
     * Scans the channel's Videos tab (newest first, no Shorts/streams tabs), at most
     * [SUB_SCAN_LIMIT] entries and [SUB_META_LIMIT] per-video metadata lookups, and stops at the
     * first video older than the subscription's start date.
     */
    private fun checkSubscription(item: QueueItem): Boolean {
        val subId = item.url.removePrefix(Subs.CHECK_PREFIX)
        val sub = Subs.get(subId) ?: return true // deleted meanwhile
        updateState(DlState(true, "بيدور على جديد: ${sub.name}", -1f, -1, Prefs.queueSize()), force = true)
        val flat = try {
            val req = YoutubeDLRequest(sub.url)
            req.addOption("--flat-playlist")
            req.addOption("--playlist-end", SUB_SCAN_LIMIT.toString())
            req.addOption("-J")
            execJson(req, "subflat")
        } catch (e: Throwable) {
            if (cancelCurrent) return true
            Subs.markChecked(subId, false)
            notifyResult("فشل التحقق من ${sub.name}", shortError(e) ?: "")
            return false
        }
        val entries = flat.optJSONArray("entries") ?: JSONArray()
        val seen = Subs.seen(subId)
        val rejected = Subs.rejected(subId)
        val newRejected = ArrayList<String>()
        class Cand(val id: String, val date: String, val pos: Int)
        val accepted = ArrayList<Cand>()
        var metaCalls = 0
        for (pos in 0 until minOf(entries.length(), SUB_SCAN_LIMIT)) {
            if (cancelCurrent || stopRequested) return true
            val e = entries.optJSONObject(pos) ?: continue
            val id = e.optString("id")
            if (id.length != 11 || id in seen || id in rejected) continue
            val flatTitle = e.optString("title")
            if (sub.keyword.isNotBlank() && flatTitle.isNotEmpty() &&
                !flatTitle.contains(sub.keyword.trim(), ignoreCase = true)
            ) continue
            val flatDur = e.optDouble("duration", -1.0)
            if (flatDur in 0.0..SHORTS_MAX_SEC) { newRejected.add(id); continue }
            if (isLiveish(e.optString("live_status"))) continue // live / upcoming: look again next time
            if (metaCalls >= SUB_META_LIMIT) break
            metaCalls++
            val m = try {
                val req = YoutubeDLRequest("https://www.youtube.com/watch?v=$id")
                req.addOption("-J")
                req.addOption("--skip-download")
                req.addOption("--no-playlist")
                execJson(req, "submeta")
            } catch (t: Throwable) {
                if (cancelCurrent) return true
                continue // e.g. members-only or temporarily unavailable; try again next check
            }
            val date = m.optString("upload_date")
            if (date.length != 8) continue
            // Newest first: everything from here on is older than the start date.
            if (date < sub.startDate) break
            if (isLiveish(m.optString("live_status"))) continue
            val dur = m.optDouble("duration", -1.0)
            if (dur in 0.0..SHORTS_MAX_SEC) { newRejected.add(id); continue }
            val title = m.optString("title").ifEmpty { flatTitle }
            if (sub.keyword.isNotBlank() && !title.contains(sub.keyword.trim(), ignoreCase = true)) continue
            accepted.add(Cand(id, date, pos))
        }
        Subs.addRejected(subId, newRejected)
        // Oldest first so the queue (and the date-prefixed names) follow upload order.
        val ordered = accepted.sortedWith(compareBy<Cand>({ it.date }, { -it.pos }))
        val jobs = if (ordered.isEmpty()) emptyList() else {
            val dir = VideoRepo.findOrCreateSubfolder(this, VideoRepo.sanitizeName(sub.name))
            ordered.map {
                QueueItem(
                    "https://www.youtube.com/watch?v=${it.id}", dir,
                    prefix = Subs.prettyYmd(it.date), sub = subId
                )
            }
        }
        if (!Prefs.replace(item, jobs)) {
            Subs.markChecked(subId, false)
            return false
        }
        Subs.addSeen(subId, ordered.map { it.id })
        Subs.markChecked(subId, true)
        if (ordered.isNotEmpty()) {
            _changed.tryEmit(Unit)
            notifyResult("فيديوهات جديدة من ${sub.name}", "${ordered.size} فيديو اتضافوا للتحميل")
        }
        return true
    }

    /** Expands a playlist into its own subfolder, keeping playlist order. */
    private fun expandPlaylist(item: QueueItem): Boolean {
        updateState(DlState(true, "بيجيب الفيديوهات من البلاي ليست…", -1f, -1, Prefs.queueSize()), force = true)
        val req = YoutubeDLRequest(item.url)
        req.addOption("--flat-playlist")
        req.addOption("--yes-playlist")
        req.addOption("-J")
        val pid = "pl-" + counter.incrementAndGet()
        currentProcessId = pid
        val out = try {
            YoutubeDL.getInstance().execute(req, pid).out
        } catch (e: YoutubeDL.CanceledException) {
            return true
        } finally {
            currentProcessId = null
        }
        if (cancelCurrent) return true
        val urls = ArrayList<String>()
        val root = JSONObject(out.substring(out.indexOf('{')))
        val entries = root.optJSONArray("entries")
        if (entries != null) {
            for (i in 0 until entries.length()) {
                val e = entries.optJSONObject(i) ?: continue
                val id = e.optString("id")
                if (id.length == 11) {
                    urls.add("https://www.youtube.com/watch?v=$id")
                } else {
                    val u = e.optString("url")
                    if (u.startsWith("http")) urls.add(u)
                }
            }
        }
        val unique = urls.distinct()
        if (unique.isEmpty()) {
            notifyResult("فشل التحميل", "البلاي ليست فاضية")
            return false
        }
        val title = root.optString("title").ifBlank { root.optString("id") }
        val folderName = VideoRepo.sanitizeName(title)
        val dir = VideoRepo.findOrCreateSubfolder(this, folderName)
        val jobs = unique.mapIndexed { i, u -> QueueItem(u, dir, i + 1, unique.size) }
        // Puts the videos where the playlist was. The caller's removeHead(item) is then a no-op.
        if (!Prefs.replace(item, jobs)) return false
        _changed.tryEmit(Unit)
        notifyResult("اتضافت البلاي ليست", "$folderName • ${unique.size} فيديو")
        return true
    }

    // ------------------------------------------------------------------ notifications

    private fun updateState(s: DlState, force: Boolean = false) {
        _state.value = s
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotifyAt < 800) return
        lastNotifyAt = now
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_PROGRESS, buildProgressNotification(s.title ?: "بيحمل…", s.progress, s.etaSec, s.queued))
    }

    private fun buildProgressNotification(title: String, progress: Float, eta: Long, queued: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancel = PendingIntent.getForegroundService(
            this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val parts = ArrayList<String>()
        if (progress >= 0) parts.add("${progress.toInt()}%")
        if (eta > 0) parts.add("باقي " + formatTime(eta * 1000))
        if (queued > 1) parts.add("في الطابور ${queued - 1}")
        val b = NotificationCompat.Builder(this, App.CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(parts.joinToString(" • "))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setProgress(100, progress.coerceIn(0f, 100f).toInt(), progress < 0)
            .addAction(0, "إلغاء", cancel)
        if (queued > 1) {
            val cancelAll = PendingIntent.getForegroundService(
                this, 2, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL_ALL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(0, "إلغاء الكل", cancelAll)
        }
        b.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    private fun notifyResult(title: String, text: String) {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, App.CH_RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            getSystemService(NotificationManager::class.java).notify(resultIds.incrementAndGet(), n)
        } catch (_: SecurityException) {}
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Downloads keep running; only the sleep timer resets on a full close.
        if (SleepTimer.isFullClose(rootIntent)) SleepTimer.reset()
    }

    override fun onTimeout(startId: Int) {
        // Android 15 dataSync time limit.
        stopRequested = true
        cancelRunning()
        finish()
    }

    override fun onDestroy() {
        stopRequested = true
        cancelRunning()
        releaseLocks()
        scope.cancel()
        _state.value = DlState(queued = Prefs.queueSize())
        super.onDestroy()
    }

    companion object {
        const val FORMAT =
            "18/best[vcodec!=none][acodec!=none][height<=480]/worst[vcodec!=none][acodec!=none]/worstaudio[ext=m4a]/worstaudio"
        private const val ACTION_CANCEL = "com.sleeptube.CANCEL"
        private const val ACTION_CANCEL_ALL = "com.sleeptube.CANCEL_ALL"
        private const val NOTIF_PROGRESS = 1001
        private const val LOCK_TIMEOUT_MS = 60 * 60 * 1000L
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val SUB_SCAN_LIMIT = 100
        private const val SUB_META_LIMIT = 30
        private const val SHORTS_MAX_SEC = 60.0
        private val counter = AtomicInteger(0)
        private val resultIds = AtomicInteger(2000)

        private val _state = MutableStateFlow(DlState())
        val state: StateFlow<DlState> = _state.asStateFlow()

        private val _changed = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        /** Emitted whenever a new file lands in the folder. */
        val changed: SharedFlow<Unit> = _changed.asSharedFlow()

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
        }

        fun cancel(ctx: Context) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, DownloadService::class.java).setAction(ACTION_CANCEL)
            )
        }

        /** Call from a visible activity: restarts a queue left over from a killed process. */
        fun resumeIfNeeded(ctx: Context) {
            if (!_state.value.active && VideoRepo.hasFolderAccess(ctx) && Net.nextEligible(ctx) != null) {
                start(ctx)
            }
        }

        private val VID = Regex("""(?:[?&]v=|youtu\.be/|/shorts/|/live/|/embed/)([A-Za-z0-9_-]{11})""")

        fun videoIdOf(url: String): String? = VID.find(url)?.groupValues?.get(1)

        fun isPlaylistUrl(url: String): Boolean {
            val u = Uri.parse(url)
            if (u.path?.contains("/playlist") == true) return true
            return url.contains("list=") && videoIdOf(url) == null
        }
    }
}
