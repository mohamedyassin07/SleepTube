package com.sleeptube

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private lateinit var playerView: PlayerView
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pendingIntent: Intent? = null
    private var leftPipAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        SleepTimer.ensureStarted()

        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            setShowNextButton(true)
            setShowPreviousButton(true)
            setShowFastForwardButton(true)
            setShowRewindButton(true)
            setShowSubtitleButton(false)
            setShowShuffleButton(false)
            controllerAutoShow = true
        }
        setContentView(playerView)
        goImmersive()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (controller?.isPlaying == true && enterPip()) return
                closeSelf()
            }
        })

        lifecycleScope.launch { SleepTimer.expired.collect { closeSelf() } }
        lifecycleScope.launch { SleepTimer.closed.collect { closeSelf() } }

        pendingIntent = intent
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = try { future.get() } catch (_: Exception) { null } ?: return@addListener
            if (isDestroyed) {
                c.release()
                return@addListener
            }
            controller = c
            playerView.player = c
            c.addListener(playerListener)
            pendingIntent?.let { handleSelection(it) }
            pendingIntent = null
            updatePipParams()
            updateKeepScreenOn()
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (controller != null) handleSelection(intent) else pendingIntent = intent
    }

    private fun handleSelection(intent: Intent) {
        val c = controller ?: return
        val uris = intent.getStringArrayListExtra(EXTRA_URIS) ?: return
        val titles = intent.getStringArrayListExtra(EXTRA_TITLES) ?: arrayListOf()
        val index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (uris.size - 1).coerceAtLeast(0))
        // Consume extras so a recreate doesn't restart the selection.
        intent.removeExtra(EXTRA_URIS)
        if (uris.isEmpty()) return
        val target = uris[index]

        val currentIds = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
        val sameItem = c.currentMediaItem?.mediaId == target
        val usable = c.playbackState == Player.STATE_READY || c.playbackState == Player.STATE_BUFFERING
        if (sameItem && usable && currentIds == uris) {
            c.play()
            return
        }

        // Save where we were on the previous item before replacing the list.
        val cur = c.currentMediaItem
        if (cur != null && usable) {
            Prefs.savePosition(cur.mediaId, c.currentPosition, c.duration.coerceAtLeast(0L))
        }
        val start = if (sameItem && usable) c.currentPosition else Prefs.startPosition(target)

        val items = uris.mapIndexed { i, u ->
            val uri = Uri.parse(u)
            MediaItem.Builder()
                .setMediaId(u)
                .setUri(uri)
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(titles.getOrNull(i) ?: "")
                        .setDisplayTitle(titles.getOrNull(i) ?: "")
                        .build()
                )
                .build()
        }
        c.setMediaItems(items, index, start)
        c.prepare()
        c.play()
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePipParams()
            updateKeepScreenOn()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            updatePipParams()
        }
    }

    private fun updateKeepScreenOn() {
        if (controller?.isPlaying == true) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ------------------------------------------------------------------ PiP

    private fun aspectRatio(): Rational {
        val vs = controller?.videoSize
        if (vs == null || vs.width <= 0 || vs.height <= 0) return Rational(16, 9)
        val r = (vs.width * vs.pixelWidthHeightRatio / vs.height).coerceIn(1f / 2.39f, 2.39f)
        return Rational((r * 1000).toInt(), 1000)
    }

    private fun pipParams(): PictureInPictureParams {
        val b = PictureInPictureParams.Builder().setAspectRatio(aspectRatio())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setAutoEnterEnabled(controller?.isPlaying == true)
            b.setSeamlessResizeEnabled(true)
        }
        return b.build()
    }

    private fun updatePipParams() {
        try { setPictureInPictureParams(pipParams()) } catch (_: Exception) {}
    }

    private fun enterPip(): Boolean {
        if (!packageManager.hasSystemFeature("android.software.picture_in_picture")) return false
        return try {
            playerView.useController = false
            playerView.hideController()
            enterPictureInPictureMode(pipParams())
        } catch (_: Exception) {
            playerView.useController = true
            false
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // API 31+ uses setAutoEnterEnabled instead.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && controller?.isPlaying == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        playerView.useController = !isInPictureInPictureMode
        SleepTimer.playerInPip = isInPictureInPictureMode
        if (!isInPictureInPictureMode) SleepTimer.playerLeftPipAt = android.os.SystemClock.elapsedRealtime()
        if (isInPictureInPictureMode) {
            playerView.hideController()
        } else if (lifecycle.currentState == Lifecycle.State.CREATED) {
            // Left PiP while stopped => the user dismissed the PiP window.
            onPipDismissed()
        } else {
            leftPipAt = android.os.SystemClock.elapsedRealtime()
            goImmersive()
        }
    }

    override fun onStop() {
        super.onStop()
        // Fallback for devices that report the PiP mode change before onStop on dismissal.
        if (!isInPictureInPictureMode && leftPipAt != 0L &&
            android.os.SystemClock.elapsedRealtime() - leftPipAt < 1500
        ) onPipDismissed()
    }

    override fun onResume() {
        super.onResume()
        leftPipAt = 0L
        SleepTimer.playerLeftPipAt = 0L
    }

    private fun onPipDismissed() {
        controller?.pause()
        closeSelf()
    }

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !isInPictureInPictureMode) goImmersive()
    }

    private fun closeSelf() {
        if (isFinishing) return
        SleepTimer.markPlayerSelfFinish()
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        SleepTimer.playerInPip = false
        controller?.removeListener(playerListener)
        playerView.player = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URIS = "uris"
        const val EXTRA_TITLES = "titles"
        const val EXTRA_INDEX = "index"

        fun start(ctx: Context, items: List<VideoItem>, index: Int) {
            val i = Intent(ctx, PlayerActivity::class.java)
                .putStringArrayListExtra(EXTRA_URIS, ArrayList(items.map { it.key }))
                .putStringArrayListExtra(EXTRA_TITLES, ArrayList(items.map { it.title }))
                .putExtra(EXTRA_INDEX, index)
            ctx.startActivity(i)
        }
    }
}
