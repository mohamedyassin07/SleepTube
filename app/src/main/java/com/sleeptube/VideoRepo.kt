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
    val dir: String? = null,
) {
    val key: String get() = uri.toString()
    val isAudio: Boolean
        get() = mime.startsWith("audio/") ||
            name.substringAfterLast('.', "").lowercase() in VideoRepo.AUDIO_EXT
}

/** A subfolder of the chosen folder (playlist downloads go into one each). */
data class FolderItem(
    val docId: String,
    val name: String,
    val uri: Uri,
    val videos: List<VideoItem>,
) {
    val key: String get() = "dir:$docId"
    val count: Int get() = videos.size
}

object VideoRepo {
    val VIDEO_EXT = setOf("mp4", "webm", "mkv", "3gp", "mov", "m4v", "avi", "ts")
    val AUDIO_EXT = setOf("m4a", "mp3", "opus", "ogg", "aac", "wav", "flac", "weba")
    private val ID_SUFFIX = Regex("""\s*\[([A-Za-z0-9_-]{11})]$""")
    private val INDEX_PREFIX = Regex("""^(\d{4}-\d{2}-\d{2}|\d{1,4}) - """)
    private val BAD_CHARS = Regex("""[\\/:*?"<>|\p{Cntrl}]""")
    private val SPACES = Regex("""\s+""")

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

    fun titleOf(fileName: String, inFolder: Boolean = false): String {
        val base = fileName.substringBeforeLast('.', fileName)
        val t = base.replace(ID_SUFFIX, "").trim()
        return if (inFolder) t.replace(INDEX_PREFIX, "").trim() else t
    }

    /** Folder-name-safe version of a playlist title. */
    fun sanitizeName(name: String): String {
        val t = name.replace(BAD_CHARS, " ").replace(SPACES, " ").trim().trim('.').take(80).trim()
        return t.ifEmpty { "Playlist" }
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

    private class Row(val docId: String, val name: String, val mime: String, val modified: Long, val size: Long)

    private fun rootDocId(tree: Uri): String = DocumentsContract.getTreeDocumentId(tree)

    private fun queryChildren(ctx: Context, tree: Uri, parentDocId: String): List<Row> {
        val out = ArrayList<Row>()
        try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
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
                    out.add(
                        Row(
                            docId, name, c.getString(2) ?: "",
                            if (c.isNull(3)) 0L else c.getLong(3),
                            if (c.isNull(4)) 0L else c.getLong(4),
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun isDir(r: Row) = r.mime == DocumentsContract.Document.MIME_TYPE_DIR

    private fun mediaOf(tree: Uri, rows: List<Row>, dir: String?): List<VideoItem> =
        rows.filter { !isDir(it) && isMedia(it.name, it.mime) }
            .map {
                VideoItem(
                    DocumentsContract.buildDocumentUriUsingTree(tree, it.docId),
                    it.name, titleOf(it.name, dir != null), idOf(it.name), it.mime, it.modified, it.size, dir
                )
            }

    private fun sortRoot(items: List<VideoItem>) =
        items.sortedWith(compareBy<VideoItem>({ it.modified }, { it.name }))

    private fun sortFolder(items: List<VideoItem>) =
        items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it: VideoItem -> it.name })

    /**
     * Media files directly in the chosen folder (dir = null; oldest first = download order)
     * or in a subfolder (sorted by name = playlist order thanks to the "001 - " prefix).
     */
    fun list(ctx: Context, dir: String? = null): List<VideoItem> {
        val tree = Prefs.folderUri ?: return emptyList()
        val items = mediaOf(tree, queryChildren(ctx, tree, dir ?: rootDocId(tree)), dir)
        return if (dir == null) sortRoot(items) else sortFolder(items)
    }

    /** Subfolders that contain media (sorted by name) and the root videos. */
    fun listAll(ctx: Context): Pair<List<FolderItem>, List<VideoItem>> {
        val tree = Prefs.folderUri ?: return Pair(emptyList(), emptyList())
        val rows = queryChildren(ctx, tree, rootDocId(tree))
        val folders = rows.filter { isDir(it) && !it.name.startsWith(".") }
            .map { r ->
                FolderItem(
                    r.docId, r.name, DocumentsContract.buildDocumentUriUsingTree(tree, r.docId),
                    list(ctx, r.docId)
                )
            }
            .filter { it.count > 0 }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it: FolderItem -> it.name })
        return Pair(folders, sortRoot(mediaOf(tree, rows, null)))
    }

    /** Finds a subfolder of the chosen folder by display name, or creates it. Returns its document id. */
    @Synchronized
    fun findOrCreateSubfolder(ctx: Context, name: String): String {
        val tree = Prefs.folderUri ?: throw IllegalStateException("no folder")
        val root = rootDocId(tree)
        queryChildren(ctx, tree, root)
            .firstOrNull { isDir(it) && it.name.equals(name, ignoreCase = true) }
            ?.let { return it.docId }
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, root)
        val uri = DocumentsContract.createDocument(
            ctx.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name
        ) ?: throw IllegalStateException("createDocument(dir) failed")
        return DocumentsContract.getDocumentId(uri)
    }

    /** True if a file containing "[id]" exists in the target folder (root when dir == null). */
    fun existsId(ctx: Context, id: String, dir: String? = null): Boolean {
        val tree = Prefs.folderUri ?: return false
        val tag = "[$id]"
        return queryChildren(ctx, tree, dir ?: rootDocId(tree))
            .any { !isDir(it) && it.name.contains(tag) && !it.name.endsWith(".part") }
    }

    /** Copies [file] into the target folder as [displayName]. Returns the new document uri. */
    fun saveToFolder(ctx: Context, file: File, dir: String? = null, displayName: String = file.name): Uri {
        val tree = Prefs.folderUri ?: throw IllegalStateException("no folder")
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, dir ?: rootDocId(tree))
        val cr = ctx.contentResolver
        val uri = DocumentsContract.createDocument(cr, parent, "application/octet-stream", displayName)
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

    /** Deletes a subfolder with everything in it. */
    fun deleteFolder(ctx: Context, folder: FolderItem): Boolean {
        folder.videos.forEach {
            Prefs.forget(it.key)
            memCache.remove(it.key)
            thumbFile(ctx, it).deleteOrWarn()
        }
        val ok = try {
            DocumentsContract.deleteDocument(ctx.contentResolver, folder.uri)
        } catch (_: Exception) { false }
        if (!ok) folder.videos.forEach { delete(ctx, it) } // fall back to file by file
        return ok
    }

    fun delete(ctx: Context, item: VideoItem): Boolean {
        val ok = try {
            DocumentsContract.deleteDocument(ctx.contentResolver, item.uri)
        } catch (_: Exception) { false }
        if (ok) {
            Prefs.forget(item.key)
            memCache.remove(item.key)
            thumbFile(ctx, item).deleteOrWarn()
        }
        return ok
    }

    private fun thumbFile(ctx: Context, item: VideoItem): File {
        val dir = File(ctx.cacheDir, "thumbs")
        // Without the cache dir the thumbnail is still shown, it just isn't cached on disk.
        dir.ensureDir()
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
