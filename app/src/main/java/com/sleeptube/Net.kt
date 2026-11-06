package com.sleeptube

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Network rules for downloads and subscription checks. */
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

    /** Subscription checks/downloads: need validated internet, and Wi-Fi/unmetered if the setting is on. */
    fun subsAllowed(s: State): Boolean = when (s) {
        State.NONE -> false
        State.METERED -> !Prefs.subsWifiOnly
        State.UNMETERED -> true
    }

    fun subsAllowed(ctx: Context): Boolean = subsAllowed(state(ctx))

    /** Arabic reason why subscription work can't run now, or null if it can. */
    fun subsBlockedReason(ctx: Context): String? = when (state(ctx)) {
        State.NONE -> "مفيش إنترنت"
        State.METERED -> if (Prefs.subsWifiOnly) "مستني Wi-Fi" else null
        State.UNMETERED -> null
    }

    /** Whether a queue item may run on the current connection. */
    fun eligible(ctx: Context, item: QueueItem, s: State = state(ctx)): Boolean {
        if (s == State.NONE) return false
        if (item.isSubCheck || item.sub != null) return subsAllowed(s)
        return true
    }

    fun nextEligible(ctx: Context): QueueItem? {
        val s = state(ctx)
        if (s == State.NONE) return null
        return Prefs.queue().firstOrNull { eligible(ctx, it, s) }
    }
}
