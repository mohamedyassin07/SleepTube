package com.sleeptube

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import kotlin.concurrent.thread

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "تقدم التحميل", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RESULT, "نتيجة التحميل", NotificationManager.IMPORTANCE_DEFAULT)
        )
        thread(name = "ytdl-init") {
            try { Ytdl.init(this) } catch (_: Throwable) {}
        }
    }

    companion object {
        const val CH_PROGRESS = "download_progress"
        const val CH_RESULT = "download_result"
    }
}
