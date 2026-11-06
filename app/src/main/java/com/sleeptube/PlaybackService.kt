package com.sleeptube

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val scope = MainScope()
    private var saveJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        player.addListener(listener)

        val activityIntent = Intent(this, PlayerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            this, 0, activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(pi)
            .setCallback(callback)
            .build()

        scope.launch { SleepTimer.expired.collect { shutdown() } }
    }

    private val callback = object : MediaSession.Callback {
        // URIs are stripped when MediaItems travel from a controller to the session; rebuild them.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val out = mediaItems.map {
                it.buildUpon()
                    .setUri(it.requestMetadata.mediaUri ?: Uri.parse(it.mediaId))
                    .build()
            }.toMutableList()
            return Futures.immediateFuture(out)
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                startSaving()
            } else {
                saveJob?.cancel()
                saveCurrent()
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            val oldId = oldPosition.mediaItem?.mediaId ?: return
            if (oldId == newPosition.mediaItem?.mediaId &&
                oldPosition.mediaItemIndex == newPosition.mediaItemIndex
            ) return
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                // Played to the end -> finished; next time it starts from 0.
                Prefs.markFinished(oldId, 0L)
            } else if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                // next / previous: remember where we left the old item.
                Prefs.savePosition(oldId, oldPosition.positionMs, 0L)
            }
            // REMOVE (playlist replaced): PlayerActivity already saved the old item.
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem == null) return
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
            ) {
                // Moving to another item in the list: resume it where it was left
                // (finished items / items saved at their very end start from 0).
                val start = Prefs.startPosition(mediaItem.mediaId)
                if (start > 0) {
                    resumedId = mediaItem.mediaId
                    player.seekTo(start)
                }
            }
            // Reached the last item of the list: pick up files that landed in the same folder
            // after playback started (e.g. a playlist/channel still downloading).
            if (player.currentMediaItemIndex >= player.mediaItemCount - 1) appendNewFromSource(false)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                saveCurrent()
                appendNewFromSource(true)
            }
            if (playbackState == Player.STATE_READY) {
                // Safety net when the saved duration was unknown: a resume position at the very
                // end would end the item at once and cascade through the list.
                val dur = player.duration
                if (resumedId != null && resumedId == player.currentMediaItem?.mediaId &&
                    dur != C.TIME_UNSET && dur > 0 && player.currentPosition >= dur - 3000
                ) player.seekTo(0)
                resumedId = null
                saveCurrent()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // A broken/missing file must not stop the night: skip to the next one.
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }
    }

    private var resumedId: String? = null

    /**
     * Appends media files from the folder the list came from that sort after the current item
     * and aren't in the player yet. If playback had ended, continues with the first new one.
     */
    private fun appendNewFromSource(continueIfEnded: Boolean) {
        if (!sourceSet) return
        val dir = sourceDir
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                try { VideoRepo.list(this@PlaybackService, dir) } catch (_: Exception) { emptyList() }
            }
            val curId = player.currentMediaItem?.mediaId ?: return@launch
            val present = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toHashSet()
            val idx = list.indexOfFirst { it.key == curId }
            if (idx < 0) return@launch
            val fresh = list.drop(idx + 1).filter { it.key !in present }
            if (fresh.isEmpty()) return@launch
            val ended = player.playbackState == Player.STATE_ENDED
            val firstNew = player.mediaItemCount
            player.addMediaItems(fresh.map { mediaItemOf(it.key, it.title) })
            if (continueIfEnded && ended) {
                player.seekTo(firstNew, Prefs.startPosition(fresh[0].key))
                player.prepare()
                player.play()
            }
        }
    }

    private fun startSaving() {
        saveJob?.cancel()
        saveJob = scope.launch {
            while (isActive) {
                delay(5000)
                saveCurrent()
            }
        }
    }

    /** Saves the current item position (or marks it finished if playback ended). */
    private fun saveCurrent() {
        val item = player.currentMediaItem ?: return
        val id = item.mediaId
        if (id.isEmpty()) return
        val dur = player.duration.let { if (it == C.TIME_UNSET || it < 0) 0L else it }
        when (player.playbackState) {
            Player.STATE_ENDED -> Prefs.markFinished(id, dur)
            Player.STATE_IDLE -> {}
            else -> Prefs.savePosition(id, player.currentPosition, dur)
        }
    }

    private fun shutdown() {
        saveCurrent()
        saveJob?.cancel()
        player.pause()
        player.stop()
        player.clearMediaItems()
        stopSelf()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    companion object {
        /** Folder the current list was opened from (null = chosen folder root). Same process. */
        @Volatile var sourceDir: String? = null
        @Volatile var sourceSet: Boolean = false

        fun mediaItemOf(key: String, title: String): MediaItem {
            val uri = Uri.parse(key)
            return MediaItem.Builder()
                .setMediaId(key)
                .setUri(uri)
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setDisplayTitle(title).build())
                .build()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        when (SleepTimer.classify(rootIntent)) {
            SleepTimer.TaskRemoval.PIP_DISMISSED -> player.pause() // listener saves the position
            SleepTimer.TaskRemoval.IGNORE -> {}
            SleepTimer.TaskRemoval.FULL_CLOSE -> {
                shutdown()
                SleepTimer.reset()
            }
        }
    }

    override fun onDestroy() {
        saveCurrent()
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
