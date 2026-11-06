package com.sleeptube

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL

object Ytdl {
    @Volatile private var initialized = false

    @Synchronized
    fun init(ctx: Context) {
        if (initialized) return
        YoutubeDL.getInstance().init(ctx.applicationContext)
        initialized = true
    }

    /** Updates yt-dlp to the nightly channel. Call on a background thread. */
    fun update(ctx: Context): String {
        init(ctx)
        val status = YoutubeDL.getInstance()
            .updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel.NIGHTLY)
        Prefs.lastUpdate = System.currentTimeMillis()
        return when (status) {
            YoutubeDL.UpdateStatus.DONE -> "اتحدث"
            YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "محدث بالفعل"
            else -> "تم"
        }
    }

    /** Updates if the last update attempt is older than [maxAgeMs]. Returns true if updated. */
    fun updateIfStale(ctx: Context, maxAgeMs: Long): Boolean {
        if (System.currentTimeMillis() - Prefs.lastUpdate < maxAgeMs) return false
        if (!Net.allowed(ctx)) return false // no internet, or Wi-Fi only and on mobile data
        return try {
            update(ctx)
            true
        } catch (_: Throwable) {
            Prefs.lastUpdate = System.currentTimeMillis() // avoid retry storms
            false
        }
    }

    fun version(ctx: Context): String? = try {
        init(ctx)
        YoutubeDL.getInstance().version(ctx.applicationContext)
    } catch (_: Throwable) { null }
}
