package com.sleeptube

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Process-wide mandatory sleep timer. Starts when the app UI is first opened in the process
 * (MainActivity / PlayerActivity), keeps counting in background, resets on full close.
 */
object SleepTimer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var startedAt: Long = 0L
    private var job: Job? = null

    private val _remaining = MutableStateFlow(-1L)
    /** Remaining milliseconds, or -1 when not running. */
    val remaining: StateFlow<Long> = _remaining.asStateFlow()

    private val _expired = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 4)
    val expired: SharedFlow<Unit> = _expired.asSharedFlow()

    private val _closed = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 4)
    /** Emitted on a full close (task swiped from recents) so remaining activities finish too. */
    val closed: SharedFlow<Unit> = _closed.asSharedFlow()

    /** Call from Activity.onCreate. No-op if already running. */
    @Synchronized
    fun ensureStarted() {
        if (job?.isActive == true) return
        startedAt = SystemClock.elapsedRealtime()
        job = scope.launch {
            while (isActive) {
                val total = Prefs.timerMinutes * 60_000L
                val left = total - (SystemClock.elapsedRealtime() - startedAt)
                if (left <= 0) {
                    _remaining.value = 0L
                    _expired.emit(Unit)
                    break
                }
                _remaining.value = left
                delay(1000)
            }
        }
    }

    /** Full close: next open starts a fresh countdown. */
    @Synchronized
    fun reset() {
        job?.cancel()
        job = null
        _remaining.value = -1L
        _closed.tryEmit(Unit)
    }

    /** Set by PlayerActivity right before it finishes itself (Back, PiP dismissed, timer). */
    @Volatile var playerSelfFinishAt: Long = 0L

    fun markPlayerSelfFinish() {
        playerSelfFinishAt = SystemClock.elapsedRealtime()
    }

    /** PlayerActivity PiP bookkeeping (dismissing PiP also removes the player's task). */
    @Volatile var playerInPip: Boolean = false
    @Volatile var playerLeftPipAt: Long = 0L

    enum class TaskRemoval { FULL_CLOSE, PIP_DISMISSED, IGNORE }

    /**
     * Services get onTaskRemoved not only when the user swipes a task away, but also when a task
     * disappears because its activity finished itself (PlayerActivity has autoRemoveFromRecents,
     * ShareActivity is excluded from recents) or when the PiP window is dismissed.
     * Only a user swipe from recents counts as a "full close".
     */
    fun classify(rootIntent: android.content.Intent?): TaskRemoval {
        val cls = rootIntent?.component?.className ?: return TaskRemoval.FULL_CLOSE
        if (cls == ShareActivity::class.java.name) return TaskRemoval.IGNORE
        if (cls == PlayerActivity::class.java.name) {
            val now = SystemClock.elapsedRealtime()
            if (playerInPip || now - playerLeftPipAt < 3000) return TaskRemoval.PIP_DISMISSED
            if (now - playerSelfFinishAt < 5000) return TaskRemoval.IGNORE
        }
        return TaskRemoval.FULL_CLOSE
    }

    fun isFullClose(rootIntent: android.content.Intent?): Boolean =
        classify(rootIntent) == TaskRemoval.FULL_CLOSE
}
