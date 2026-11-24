package com.resonance.music.data.repository

import com.google.gson.JsonSyntaxException
import com.resonance.music.data.api.models.SubsonicError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException

class ServerHealthTest {
    private var now = 1_000L
    private val gate = HealthGate(windowMs = 20_000L, clock = { now })

    @Test
    fun `a fresh gate lets live calls through`() {
        assertFalse(gate.isDown())
    }

    @Test
    fun `one failure holds the gate for the window`() {
        gate.markDown()
        assertTrue(gate.isDown())
        now += 19_999L
        assertTrue(gate.isDown())
    }

    @Test
    fun `the gate reopens when the window passes`() {
        gate.markDown()
        now += 20_000L
        assertFalse(gate.isDown())
    }

    @Test
    fun `a read that serves the saved copy holds the gate closed`() {
        gate.markDown()
        // Browsing offline: a read every few seconds must not open the gate and
        // spend another connect attempt.
        repeat(5) {
            now += 5_000L
            assertTrue(gate.holdDown())
            assertTrue(gate.isDown())
        }
    }

    @Test
    fun `the hold ends so a recovered server is noticed`() {
        gate.markDown()
        now += 119_000L
        assertTrue(gate.holdDown())
        // Past the hard limit the hold refuses, so the next read tries the server
        // instead of serving the cache forever.
        now += 2_000L
        assertFalse(gate.holdDown())
    }

    @Test
    fun `the hold is off before any failure`() {
        assertFalse(gate.holdDown())
    }

    @Test
    fun `a success reopens the gate early`() {
        gate.markDown()
        gate.markUp()
        assertFalse(gate.isDown())
    }

    @Test
    fun `a second failure restarts the window`() {
        gate.markDown()
        now += 15_000L
        gate.markDown()
        now += 15_000L
        assertTrue(gate.isDown())
    }

    @Test
    fun `connectivity failures count as unreachable`() {
        assertTrue(isUnreachable(IOException("boom")))
        assertTrue(isUnreachable(UnknownHostException("no host")))
        assertTrue(isUnreachable(SocketTimeoutException("timed out")))
        assertTrue(isUnreachable(EOFException("cut off")))
    }

    @Test
    fun `a portal page instead of JSON counts as unreachable`() {
        assertTrue(isUnreachable(JsonSyntaxException("<!doctype html>")))
    }

    @Test
    fun `a server that answers no is not a connectivity failure`() {
        assertFalse(isUnreachable(SubsonicException(SubsonicError(code = 40, message = "Wrong password"))))
        assertFalse(isUnreachable(IllegalArgumentException("bad argument")))
        assertFalse(isUnreachable(CancellationException("screen closed")))
    }
}
