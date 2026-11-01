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
        if (!VideoRepo.hasFolderAccess(this)) {
            Prefs.pendingUrl = url
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Toast.makeText(this, "اختار فولدر التحميل الأول", Toast.LENGTH_SHORT).show()
            return
        }
        Prefs.enqueue(url)
        DownloadService.start(this)
        Toast.makeText(this, "اتضاف للتحميل", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private val URL_RE = Regex("""https?://\S+""")
    }
}
