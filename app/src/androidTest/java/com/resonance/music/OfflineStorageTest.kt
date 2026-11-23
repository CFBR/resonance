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
 * Exercises OfflineStorage against the real DocumentsContract call paths via
 * an in-process documents provider. The Samsung provider itself needs a picker
 * grant and is covered by the manual acceptance pass in the app.
 */
@RunWith(AndroidJUnit4::class)
class OfflineStorageTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var settings: SettingsStore
    private lateinit var storage: OfflineStorage
    /** Whatever the installed app had picked before this test, restored after. */
    private var previousRoot: String? = null
    private var capturedRoot = false

    @Before
    fun setUp() = runBlocking {
        val treeUri = DocumentsContract.buildTreeDocumentUri(FakeDocumentsProvider.AUTHORITY, FakeDocumentsProvider.ROOT_ID)
        settings = SettingsStore(context)
        previousRoot = settings.storedOfflineRoot()
        capturedRoot = true
        settings.setOfflineRoot(treeUri.toString())
        settings.offlineRoot.first { it == treeUri.toString() }
        storage = OfflineStorage(context, settings)
        Unit
    }

    @After
    fun tearDown() = runBlocking {
        // Point the app back where it was; leaving the fake tree or a null root
        // behind would strand a device that runs instrumentation between real
        // uses. Skip it when setUp failed before it could read the old value,
        // or the restore would write the very null this guard prevents.
        if (capturedRoot) {
            settings.setOfflineRoot(previousRoot)
            assertEquals(previousRoot, settings.storedOfflineRoot())
        }
        Unit
    }

    @Test
    fun writeReadReplaceAndDelete() = runBlocking {
        val path = "Resonance/TestArtist/TestAlbum/01 - Probe [0123456789ab].flac"
        assertFalse(storage.exists(path))

        val payload = ByteArray(4096) { (it % 251).toByte() }
        storage.write(path) { it.write(payload) }

        assertTrue(storage.exists(path))
        val uri = storage.documentUri(path)
        assertNotNull(uri)
        context.contentResolver.openInputStream(uri!!).use { input ->
            assertEquals(payload.size, input!!.readBytes().size)
        }

        // A second write replaces the file instead of leaving a copy.
        storage.write(path) { it.write(ByteArray(8) { 7 }) }
        context.contentResolver.openInputStream(storage.documentUri(path)!!).use { input ->
            assertEquals(8, input!!.readBytes().size)
        }

        assertTrue(storage.delete(path))
        assertFalse(storage.exists(path))
    }

    @Test
    fun aCrashedPartFileIsNotMistakenForTheDownload() = runBlocking {
        val path = "Resonance/TestArtist/TestAlbum/02 - Second [aaaaaaaaaaaa].flac"
        val partPath = "$path.part"
        storage.write(partPath) { it.write(ByteArray(3)) }
        assertTrue(storage.exists(partPath))

        storage.write(path) { it.write(ByteArray(16) { 9 }) }

        assertFalse(storage.exists(partPath))
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

    @Test
    fun theStructureCacheDoesNotHideFilesPushedFromOutside() = runBlocking {
        val path = "Resonance/TestArtist/TestAlbum/03 - Outside [cccccccccccc].flac"
        // Prime the cache with a listing that does not contain the file.
        assertFalse(storage.exists(path))

        // A computer pushes the file while the process keeps running: the
        // provider sees the disk, the cache still answers from the old listing.
        val pushed = File(context.getDir("fake_documents", Context.MODE_PRIVATE), path)
        pushed.parentFile!!.mkdirs()
        pushed.writeBytes(ByteArray(16) { 5 })
        assertFalse(storage.exists(path))

        // Import drops the structure cache and the next lookup sees the file.
        storage.dropStructureCache()
        assertTrue(storage.exists(path))
        assertTrue(storage.delete(path))
    }
}
