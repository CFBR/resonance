package com.resonance.music

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.resonance.music.data.download.OfflineStorage
import com.resonance.music.data.repository.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Exercises the real Storage Access Framework on a device: write a file through
 * a tree root, read it back, replace it, and delete it. The tree root points at
 * the app's own external files directory, which needs no user grant.
 */
@RunWith(AndroidJUnit4::class)
class OfflineStorageTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val authority = "com.android.externalstorage.documents"

    private lateinit var settings: SettingsStore
    private lateinit var storage: OfflineStorage
    private lateinit var root: File

    @Before
    fun setUp() = runBlocking {
        root = File(context.getExternalFilesDir(null), "OfflineStorageTest").apply { mkdirs() }
        val relative = root.absolutePath.removePrefix("/storage/emulated/0/")
        val treeUri = DocumentsContract.buildTreeDocumentUri(authority, "primary:$relative")
        settings = SettingsStore(context)
        settings.setOfflineRoot(treeUri.toString())
        settings.offlineRoot.first { it == treeUri.toString() }
        storage = OfflineStorage(context, settings)
    }

    @After
    fun tearDown() = runBlocking {
        settings.setOfflineRoot(null)
        root.deleteRecursively()
    }

    @Test
    fun writeReadReplaceAndDelete() = runBlocking {
        val path = "Resonance/TestArtist/TestAlbum/01 - Probe [0123456789ab].flac"
        assertFalse(storage.exists(path))

        val payload = ByteArray(4096) { (it % 251).toByte() }
        storage.write(path, "audio/flac") { it.write(payload) }

        assertTrue(storage.exists(path))
        val uri = storage.documentUri(path)
        assertNotNull(uri)
        context.contentResolver.openInputStream(uri!!).use { input ->
            assertEquals(payload.size, input!!.readBytes().size)
        }

        // A second write replaces the file instead of leaving a copy.
        storage.write(path, "audio/flac") { it.write(ByteArray(8) { 7 }) }
        context.contentResolver.openInputStream(storage.documentUri(path)!!).use { input ->
            assertEquals(8, input!!.readBytes().size)
        }

        assertTrue(storage.delete(path))
        assertFalse(storage.exists(path))
        assertFalse(root.resolve("Resonance/TestArtist/TestAlbum/01 - Probe [0123456789ab].flac").exists())
    }

    @Test
    fun aCrashedPartFileIsNotMistakenForTheDownload() = runBlocking {
        val path = "Resonance/TestArtist/TestAlbum/02 - Second [aaaaaaaaaaaa].flac"
        val part = File(root, "Resonance/TestArtist/TestAlbum/02 - Second [aaaaaaaaaaaa].flac.part")
        part.parentFile!!.mkdirs()
        part.writeBytes(byteArrayOf(1, 2, 3))

        storage.write(path, "audio/flac") { it.write(ByteArray(16) { 9 }) }

        assertFalse(part.exists())
        assertTrue(storage.exists(path))
        assertTrue(storage.delete(path))
    }

    @Test
    fun absentFilesResolveToNothing() = runBlocking {
        val missing = "Resonance/TestArtist/Other/03 - Missing [bbbbbbbbbbbb].flac"
        assertFalse(storage.exists(missing))
        assertNull(storage.documentUri(missing))
        assertFalse(storage.delete(missing))
    }
}
