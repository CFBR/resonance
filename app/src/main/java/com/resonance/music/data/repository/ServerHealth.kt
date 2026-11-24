package com.resonance.music.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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

                    // Losing the network is evidence the server is out of reach,
                    // and it is the cheap moment to say so. A tunnel that holds
                    // the default routes after Wi-Fi drops still looks online, so
                    // waiting for the next read to fail costs that read its whole
                    // timeout; starting down means it reads the saved copy instead.
                    override fun onLost(network: android.net.Network) = gate.markDown()
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

    /**
     * True when a transfer that waits for unmetered Wi-Fi may run now. Uses the
     * same real-transport rule as [hasNetwork]: the VPN set aside, a tunnel is
     * not a connection to transfer over.
     */
    fun hasUnmetered(): Boolean = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return true
        connectivity.allNetworks.any { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@any false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }.getOrDefault(true)

    fun noteSuccess() = gate.markUp()

    fun noteFailure() = gate.markDown()

    /**
     * Cheap check: is there a network the app can actually reach the server on?
     *
     * The active network is not enough to answer that. A VPN keeps the default
     * routes after the network under it goes away, so it stays the active
     * network and still reports INTERNET and VALIDATED while its packets go
     * nowhere. Tailscale does exactly this for minutes after Wi-Fi drops, and
     * every read in that window spends its whole timeout finding out. So the
     * question asked here is whether any network the app can see is a real
     * transport, with the VPN set aside: a tunnel over nothing is not a
     * connection.
     */
    private fun hasNetwork(): Boolean = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return true
        connectivity.allNetworks.any { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@any false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }.getOrDefault(true)

    companion object {
        /** Long enough to cover the screens a user opens in a row, short enough
         *  that a restored server is picked up without a restart. */
        const val WINDOW_MS = 20_000L
    }
}
