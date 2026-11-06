package com.sleeptube

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Network rules for every download (shared videos, playlists incl. expansion, subscription
 * checks and their downloads) and for the yt-dlp / app self-updates:
 * validated internet is required, and with "التحميل على Wi-Fi بس" on, an unmetered/Wi-Fi network.
 */
object Net {
    enum class State { NONE, METERED, UNMETERED }

    fun state(ctx: Context): State {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return State.NONE
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) return State.NONE
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            ) State.UNMETERED else State.METERED
        } catch (_: Exception) {
            State.NONE
        }
    }

    fun allowed(s: State): Boolean = when (s) {
        State.NONE -> false
        State.METERED -> !Prefs.wifiOnly
        State.UNMETERED -> true
    }

    fun allowed(ctx: Context): Boolean = allowed(state(ctx))

    /** Arabic reason why downloads can't run now, or null if they can. */
    fun blockedReason(ctx: Context): String? = blockedReason(state(ctx))

    fun blockedReason(s: State): String? = when (s) {
        State.NONE -> "مفيش إنترنت"
        State.METERED -> if (Prefs.wifiOnly) "مستني Wi-Fi" else null
        State.UNMETERED -> null
    }

    /** Whether a queue item may run on the current connection (same rule for every kind). */
    @Suppress("UNUSED_PARAMETER")
    fun eligible(ctx: Context, item: QueueItem, s: State = state(ctx)): Boolean = allowed(s)

    fun nextEligible(ctx: Context): QueueItem? {
        if (!allowed(ctx)) return null
        return Prefs.queueHead()
    }
}
