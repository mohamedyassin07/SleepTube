package com.sleeptube

import android.annotation.SuppressLint
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
import android.os.IBinder
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
            ACTION_CANCEL -> cancelRunning()
            ACTION_CANCEL_ALL -> {
                Prefs.clearQueue()
                cancelRunning()
            }
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
        if (loopRunning) return
        if (Prefs.queueHead() == null) {
            finish()
            return
        }
        loopRunning = true
        scope.launch {
            withContext(Dispatchers.IO) { runQueue() }
            // Back on main thread: onStartCommand also runs here, so no enqueue can slip between
            // this check and stopSelf().
            loopRunning = false
            if (Prefs.queueHead() != null && VideoRepo.hasFolderAccess(this@DownloadService)) {
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

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepTube:download").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SleepTube:download").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Throwable) {}
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Throwable) {}
        wakeLock = null
        wifiLock = null
    }

    // ------------------------------------------------------------------ worker (IO thread)

    private fun runQueue() {
        acquireLocks()
        try {
            Ytdl.init(this)
        } catch (e: Throwable) {
            notifyResult("فشل تجهيز yt-dlp", e.message ?: "")
            return
        }
        Ytdl.updateIfStale(this, 24 * 3600_000L)
        while (true) {
            val url = Prefs.queueHead() ?: break
            if (!VideoRepo.hasFolderAccess(this)) {
                notifyResult("اختار فولدر التحميل", "افتح التطبيق واختار الفولدر")
                break
            }
            cancelCurrent = false
            try {
                processUrl(url)
            } catch (e: Throwable) {
                notifyResult("فشل التحميل", e.message?.take(200) ?: url)
            }
            Prefs.removeHead(url)
        }
    }

    private fun processUrl(url: String) {
        if (isPlaylistUrl(url)) {
            expandPlaylist(url)
            return
        }
        val vid = videoIdOf(url)
        if (vid != null && VideoRepo.existsId(this, vid)) return

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
                    return
                } catch (e: Throwable) {
                    if (cancelCurrent) return
                    lastError = e
                    if (attempt == 0 && System.currentTimeMillis() - Prefs.lastUpdate > 3600_000L) {
                        updateState(_state.value.copy(title = "بيحدث yt-dlp…"), force = true)
                        if (!Ytdl.updateIfStale(this, 3600_000L)) break
                        tmp.listFiles()?.forEach { it.deleteRecursively() }
                    } else break
                }
            }
            if (cancelCurrent) return
            if (!ok) {
                notifyResult("فشل التحميل", shortError(lastError) ?: url)
                return
            }
            val file = tmp.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                ?.maxByOrNull { it.length() }
                ?: run {
                    notifyResult("فشل التحميل", "مفيش ملف اتحمل")
                    return
                }
            val id = VideoRepo.idOf(file.name)
            if (id != null && VideoRepo.existsId(this, id)) return
            updateState(_state.value.copy(title = VideoRepo.titleOf(file.name), progress = 100f), force = true)
            VideoRepo.saveToFolder(this, file)
            _changed.tryEmit(Unit)
            notifyResult("خلص التحميل", VideoRepo.titleOf(file.name))
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

    private fun expandPlaylist(url: String) {
        updateState(DlState(true, "بيجيب الفيديوهات من البلاي ليست…", -1f, -1, Prefs.queueSize()), force = true)
        val req = YoutubeDLRequest(url)
        req.addOption("--flat-playlist")
        req.addOption("--yes-playlist")
        req.addOption("-J")
        val pid = "pl-" + counter.incrementAndGet()
        currentProcessId = pid
        val out = try {
            YoutubeDL.getInstance().execute(req, pid).out
        } catch (e: YoutubeDL.CanceledException) {
            return
        } finally {
            currentProcessId = null
        }
        if (cancelCurrent) return
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
        if (urls.isEmpty()) {
            notifyResult("فشل التحميل", "البلاي ليست فاضية")
            return
        }
        // Puts the videos where the playlist was. The caller's removeHead(url) is then a no-op.
        Prefs.replace(url, urls)
        notifyResult("اتضافت البلاي ليست", "${urls.size} فيديو")
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
        cancelRunning()
        finish()
    }

    override fun onDestroy() {
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
            if (!_state.value.active && Prefs.queueHead() != null && VideoRepo.hasFolderAccess(ctx)) {
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
