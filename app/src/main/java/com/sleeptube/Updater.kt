package com.sleeptube

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the rolling `latest` GitHub release for a newer build and installs it.
 * The release carries a version.json asset ({"versionCode": N}) and "versionCode: N" in its body.
 */
object Updater {
    /** owner/repo whose releases are checked. One-line change if releases move to another repo. */
    const val REPO = "mohamedyassin07/SleepTube"
    private const val TAG = "latest"
    private const val DAY = 24 * 3600_000L

    sealed class Result {
        object UpToDate : Result()
        object Unreachable : Result()
        data class Available(val versionCode: Int, val apkUrl: String, val size: Long) : Result()
    }

    private fun get(url: String, accept: String = "application/vnd.github+json"): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("Accept", accept)
            c.setRequestProperty("User-Agent", "SleepTube-Android")
            if (c.responseCode !in 200..299) null else c.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    /** Blocking; call on a background thread. Never throws. */
    fun check(): Result {
        return try {
            val body = get("https://api.github.com/repos/$REPO/releases/tags/$TAG") ?: return Result.Unreachable
            val rel = JSONObject(body)
            val assets = rel.optJSONArray("assets")
            var apkUrl: String? = null
            var apkSize = 0L
            var versionUrl: String? = null
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                when (a.optString("name")) {
                    "SleepTube.apk" -> {
                        apkUrl = a.optString("browser_download_url")
                        apkSize = a.optLong("size")
                    }
                    "version.json" -> versionUrl = a.optString("browser_download_url")
                }
            }
            var code = Regex("""versionCode:\s*(\d+)""").find(rel.optString("body"))?.groupValues?.get(1)?.toIntOrNull()
            if (code == null && versionUrl != null) {
                code = get(versionUrl, "application/octet-stream")?.let {
                    try { JSONObject(it).optInt("versionCode", 0) } catch (_: Exception) { 0 }
                }
            }
            if (code == null || apkUrl.isNullOrEmpty()) return Result.Unreachable
            if (code > BuildConfig.VERSION_CODE) Result.Available(code, apkUrl, apkSize) else Result.UpToDate
        } catch (_: Exception) {
            Result.Unreachable
        }
    }

    /** Automatic check at most once per 24h. Returns null when skipped. */
    fun checkIfDue(): Result? {
        val now = System.currentTimeMillis()
        if (now - Prefs.lastAppUpdateCheck < DAY) return null
        Prefs.lastAppUpdateCheck = now
        return check()
    }

    /** Downloads the APK to cache with progress (0..1). Blocking. */
    fun download(ctx: Context, url: String, onProgress: (Float) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates")
        if (!dir.ensureDir()) throw IllegalStateException("cannot create ${dir.path}")
        val out = File(dir, "SleepTube.apk")
        val tmp = File(dir, "SleepTube.apk.part")
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "SleepTube-Android")
            c.setRequestProperty("Accept", "application/octet-stream")
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            c.inputStream.use { input ->
                tmp.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        if (!out.deleteOrWarn()) throw IllegalStateException("cannot replace ${out.path}")
        if (!tmp.renameTo(out)) throw IllegalStateException("rename failed")
        return out
    }

    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    /** Opens the "allow from this source" screen for this app. */
    fun openInstallPermissionSettings(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
