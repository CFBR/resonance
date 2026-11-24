package com.resonance.music.data.repository

import android.content.Context
import android.net.ConnectivityManager
import com.google.gson.JsonParseException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers that the server just failed to answer, so the screens that follow
 * a failed one read their saved copy instead of waiting out another timeout.
 *
 * A blackholed route is the slow case, not a refused one: with Wi-Fi up but the
 * tunnel down, the connect attempt runs until the socket timeout, and every
 * screen the user opens does that again. One failure is enough evidence to
 * serve the cache for [ServerHealth.WINDOW_MS].
 */
internal class HealthGate(
    private val windowMs: Long,
    private val clock: () -> Long,
    private val retryAfterMs: Long = RETRY_AFTER_MS
) {
    private var downUntil = 0L
    private var hardUntil = 0L

    fun isDown(): Boolean = clock() < downUntil

    fun markDown() {
        val now = clock()
        downUntil = now + windowMs
        hardUntil = now + retryAfterMs
    }

    /**
     * Keeps the gate closed while the saved copy is in use, so a user browsing
     * offline does not eat a connect attempt every window. Returns false once
     * the hard limit passes: a server that came back must be noticed without a
     * restart, and every read sliding the window would hide that forever.
     */
    fun holdDown(): Boolean {
        val now = clock()
        if (now >= hardUntil) return false
        downUntil = now + windowMs
        return true
    }

    fun markUp() {
        downUntil = 0L
        hardUntil = 0L
    }

    companion object {
        /** Longest the gate may stay held without trying the server again. */
        const val RETRY_AFTER_MS = 2 * 60_000L
    }
}

/**
 * True when an error means "the server gave no usable answer", as opposed to
 * "the server answered and said no". The second kind is the app's business to
 * report; the first kind is what the saved copy is for.
 *
 * A hotspot that redirects to a portal answers 200 with a page, which arrives
 * as a JSON parse failure, so that counts here too.
 */
internal fun isUnreachable(error: Throwable): Boolean =
    error is IOException || error is JsonParseException

@Singleton
class ServerHealth @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val gate = HealthGate(WINDOW_MS, clock = { System.currentTimeMillis() })

    /** A network came back, so stop trusting the old failure. */
    init {
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) = gate.markUp()
                    override fun onLost(network: android.net.Network) = gate.markUp()
                }
            )
        }
    }

    /** True when a live read is worth attempting now. */
    fun available(): Boolean {
        if (!hasNetwork()) return false
        if (gate.isDown()) {
            if (gate.holdDown()) return false
            // Held long enough; let this read try the server so a recovered one
            // is noticed.
        }
        return true
    }

    /**
     * True while reads come from the saved copy, for a screen that wants to say
     * so. Polled rather than pushed: the gate reopens on its own when the window
     * lapses, and a screen that stayed open through a reconnect has to notice.
     */
    fun isOffline(): Boolean = !hasNetwork() || gate.isDown()

    fun noteSuccess() = gate.markUp()

    fun noteFailure() = gate.markDown()

    /** Cheap check: is there a network at all? Airplane mode needs no timeout. */
    private fun hasNetwork(): Boolean = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)

    companion object {
        /** Long enough to cover the screens a user opens in a row, short enough
         *  that a restored server is picked up without a restart. */
        const val WINDOW_MS = 20_000L
    }
}
