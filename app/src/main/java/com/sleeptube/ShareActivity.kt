package com.sleeptube

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** Invisible share target: enqueue the shared link and leave immediately. */
class ShareActivity : Activity() {
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
    }

    override fun onResume() {
        super.onResume()
        if (handled) return
        handled = true
        try {
            handle()
        } catch (e: Exception) {
            Toast.makeText(this, "حصلت مشكلة: ${e.message}", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private fun handle() {
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent?.getStringExtra(Intent.EXTRA_SUBJECT)
            ?: ""
        val url = URL_RE.find(text)?.value?.trimEnd('.', ',', ')', ']', '"', '\'')
        if (url == null) {
            Toast.makeText(this, "مفيش لينك", Toast.LENGTH_SHORT).show()
            return
        }
        if (Subs.isChannelUrl(url)) {
            // A channel: offer to follow it instead of downloading.
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_ADD_SUB, url)
            )
            return
        }
        if (!VideoRepo.hasFolderAccess(this)) {
            // Keep it in the persisted queue; MainActivity starts the download after a folder is picked.
            Prefs.enqueue(QueueItem(url))
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Toast.makeText(this, "اختار فولدر التحميل الأول", Toast.LENGTH_SHORT).show()
            return
        }
        Prefs.enqueue(QueueItem(url))
        DownloadService.start(this)
        val msg = when (Net.blockedReason(this)) {
            null -> "اتضاف للتحميل"
            "مستني Wi-Fi" -> "اتضاف للتحميل، مستني Wi-Fi"
            else -> "اتضاف للتحميل، مستني إنترنت"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private val URL_RE = Regex("""https?://\S+""")
    }
}
