package com.sleeptube

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.LruCache
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream

data class VideoItem(
    val uri: Uri,
    val name: String,
    val title: String,
    val videoId: String?,
    val mime: String,
    val modified: Long,
    val size: Long,
) {
    val key: String get() = uri.toString()
    val isAudio: Boolean
        get() = mime.startsWith("audio/") ||
            name.substringAfterLast('.', "").lowercase() in VideoRepo.AUDIO_EXT
}

object VideoRepo {
    val VIDEO_EXT = setOf("mp4", "webm", "mkv", "3gp", "mov", "m4v", "avi", "ts")
    val AUDIO_EXT = setOf("m4a", "mp3", "opus", "ogg", "aac", "wav", "flac", "weba")
    private val ID_SUFFIX = Regex("""\s*\[([A-Za-z0-9_-]{11})]$""")

    private val memCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun hasFolderAccess(ctx: Context): Boolean {
        val folder = Prefs.folderUri ?: return false
        return ctx.contentResolver.persistedUriPermissions.any {
            it.uri == folder && it.isReadPermission && it.isWritePermission
        }
    }

    fun takeFolder(ctx: Context, uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val cr = ctx.contentResolver
        val old = Prefs.folderUri
        cr.takePersistableUriPermission(uri, flags)
        if (old != null && old != uri) {
            try { cr.releasePersistableUriPermission(old, flags) } catch (_: Exception) {}
        }
        Prefs.folderUri = uri
    }

    fun folderName(ctx: Context): String? {
        val folder = Prefs.folderUri ?: return null
        return try {
            DocumentFile.fromTreeUri(ctx, folder)?.name
        } catch (_: Exception) { null } ?: folder.lastPathSegment
    }

    fun titleOf(fileName: String): String {
        val base = fileName.substringBeforeLast('.', fileName)
        return base.replace(ID_SUFFIX, "").trim()
    }

    fun idOf(fileName: String): String? {
        val base = fileName.substringBeforeLast('.', fileName)
        return ID_SUFFIX.find(base)?.groupValues?.get(1)
    }

    private fun isMedia(name: String, mime: String): Boolean {
        if (name.startsWith(".")) return false
        if (mime.startsWith("video/") || mime.startsWith("audio/")) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in VIDEO_EXT || ext in AUDIO_EXT
    }

    /** Lists media files in the chosen folder, oldest first. */
    fun list(ctx: Context): List<VideoItem> {
        val tree = Prefs.folderUri ?: return emptyList()
        val out = ArrayList<VideoItem>()
        try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE,
            )
            ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    if (!isMedia(name, mime)) continue
                    val modified = if (c.isNull(3)) 0L else c.getLong(3)
                    val size = if (c.isNull(4)) 0L else c.getLong(4)
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                    out.add(VideoItem(uri, name, titleOf(name), idOf(name), mime, modified, size))
                }
            }
        } catch (_: Exception) {
        }
        out.sortWith(compareBy<VideoItem>({ it.modified }, { it.name }))
        return out
    }

    fun existsId(ctx: Context, id: String): Boolean {
        val tree = Prefs.folderUri ?: return false
        val tag = "[$id]"
        try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
            ctx.contentResolver.query(
                children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(0) ?: continue
                    if (n.contains(tag) && !n.endsWith(".part")) return true
                }
            }
        } catch (_: Exception) {
        }
        return false
    }

    /** Copies [file] into the chosen folder keeping its file name. Returns the new document uri. */
    fun saveToFolder(ctx: Context, file: File): Uri {
        val tree = Prefs.folderUri ?: throw IllegalStateException("no folder")
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree)
        )
        val cr = ctx.contentResolver
        val uri = DocumentsContract.createDocument(cr, parent, "application/octet-stream", file.name)
            ?: throw IllegalStateException("createDocument failed")
        try {
            cr.openOutputStream(uri, "w")?.use { os ->
                file.inputStream().use { it.copyTo(os, 256 * 1024) }
            } ?: throw IllegalStateException("openOutputStream failed")
        } catch (e: Exception) {
            try { DocumentsContract.deleteDocument(cr, uri) } catch (_: Exception) {}
            throw e
        }
        return uri
    }

    fun delete(ctx: Context, item: VideoItem): Boolean {
        val ok = try {
            DocumentsContract.deleteDocument(ctx.contentResolver, item.uri)
        } catch (_: Exception) { false }
        if (ok) {
            Prefs.forget(item.key)
            memCache.remove(item.key)
            thumbFile(ctx, item).delete()
        }
        return ok
    }

    private fun thumbFile(ctx: Context, item: VideoItem): File {
        val dir = File(ctx.cacheDir, "thumbs").apply { mkdirs() }
        val h = (item.key.hashCode().toLong() and 0xffffffffL).toString(16)
        return File(dir, "${h}_${item.size}.jpg")
    }

    fun cachedThumb(item: VideoItem): Bitmap? = memCache.get(item.key)

    /** Loads (or generates) a thumbnail. Call on a background thread. Returns null for audio. */
    fun thumbnail(ctx: Context, item: VideoItem): Bitmap? {
        if (item.isAudio) return null
        memCache.get(item.key)?.let { return it }
        val f = thumbFile(ctx, item)
        if (f.exists()) {
            BitmapFactory.decodeFile(f.absolutePath)?.let {
                memCache.put(item.key, it)
                return it
            }
        }
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, item.uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val atUs = if (dur > 20_000) 10_000_000L else dur * 500L // 10s in, or middle of short clip
            val frame = r.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.frameAtTime
                ?: return null
            val w = 320
            val h = (frame.height * w / frame.width.coerceAtLeast(1)).coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(frame, w, h, true)
            if (scaled !== frame) frame.recycle()
            try {
                FileOutputStream(f).use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            } catch (_: Exception) {}
            memCache.put(item.key, scaled)
            return scaled
        } catch (_: Throwable) {
            return null
        } finally {
            try { r.release() } catch (_: Throwable) {}
        }
    }
}
