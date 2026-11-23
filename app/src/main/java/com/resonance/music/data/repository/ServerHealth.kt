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
    private val clock: () -> Long
) {
    private var downUntil = 0L

    fun isDown(): Boolean = clock() < downUntil

    fun markDown() {
        downUntil = clock() + windowMs
    }

    fun markUp() {
        downUntil = 0L
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
    private val gate = HealthGate(WINDOW_MS) { System.currentTimeMillis() }

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
    fun available(): Boolean = hasNetwork() && !gate.isDown()

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
