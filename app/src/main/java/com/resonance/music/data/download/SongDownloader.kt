package com.resonance.music.data.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import coil.network.HttpException
import coil.request.ErrorResult
import com.resonance.music.data.api.SubsonicApi
import com.resonance.music.data.api.SubsonicApiHelper
import com.resonance.music.data.api.models.SongItem
import com.resonance.music.data.repository.LyricsCache
import com.resonance.music.data.repository.resolveLyrics
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import androidx.work.*
import com.resonance.music.data.repository.AuthRepository
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import androidx.core.graphics.drawable.toBitmap
import android.graphics.Bitmap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filter

enum class DownloadResult { STARTED, ALREADY_QUEUED, ALREADY_DOWNLOADED }

@Singleton
class SongDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: SubsonicApi,
    private val apiHelper: SubsonicApiHelper,
    private val library: OfflineLibrary,
    private val auth: AuthRepository,
    private val storage: OfflineStorage,
    private val okHttp: OkHttpClient,
    private val notifications: DownloadNotifications,
    private val lyricsCache: LyricsCache
) {
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val preferences = context.getSharedPreferences("song_downloads", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            auth.generation.filter { it % 2L == 0L }.map { library.account() }
                .distinctUntilChanged().collect { account ->
                    if (account != null && library.records(account).any { it.downloadId < 0 && it.error == null }) schedule(account)
                }
        }
    }

    suspend fun download(song: SongItem): DownloadResult {
        val result = queue(listOf(song))
        return if (result > 0) DownloadResult.STARTED else {
            if (library.tracks.value.any { it.record.song.id == song.id && it.status == OfflineStatus.READY })
                DownloadResult.ALREADY_DOWNLOADED else DownloadResult.ALREADY_QUEUED
        }
    }

    suspend fun queue(songs: List<SongItem>, wifiOnly: Boolean = false): Int = withContext(Dispatchers.IO) {
        val account = library.account() ?: throw IOException("Sign in to download music.")
        var added = 0
        mutex.withLock {
            val queued = mutableListOf<OfflineRecord>()
            for (song in songs.distinctBy { it.id }) {
                if (library.account() != account) throw IOException("Your account changed. Try again.")
                val url = apiHelper.getDownloadUrl(song.id)?.toHttpUrl() ?: throw IOException("Sign in to download music.")
                val key = downloadKey(url.newBuilder().query(null).build().toString(), url.queryParameter("u").orEmpty(), song.id)
                val previous = library.record(key)
                if (previous != null && library.inspect(previous).status != OfflineStatus.FAILED) continue
                queued += OfflineRecord(key, account, song, wifiOnly = wifiOnly)
                added++
            }
            if (library.account() != account) throw IOException("Your account changed. Try again.")
            library.saveAll(queued)
        }
        schedule(account)
        library.refresh()
        added
    }

    /** Songs still awaiting a transfer, for the foreground notification. */
    suspend fun pendingCount(account: String): Int = pendingTransferCount(library.records(account))

    private fun schedule(account: String) {
        for (wifiOnly in listOf(false, true)) {
            if (library.records(account).none { it.wifiOnly == wifiOnly && it.error == null }) continue
            WorkManager.getInstance(context).enqueueUniqueWork(
                "downloads_${account}_$wifiOnly", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<DownloadWorker>()
                    .setInputData(workDataOf("account" to account, "wifiOnly" to wifiOnly))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(
                        if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
            )
        }
    }

    /**
     * Fetches every saved song's cover that is not already in the artwork
     * store, so an offline library browses with complete artwork. A cover the
     * server cannot serve counts as done, not as a failure: audio never
     * depended on it. A run of failures means the network dropped mid-pass;
     * the worker retries, and the next pass skips whatever landed.
     */
    suspend fun prefetchArtwork(account: String): Int {
        if (library.account() != account) return 0
        val absent = library.absentArtwork()
        val covers = missingArtworkCovers(library.browseSongs()) { cover ->
            cover in absent || library.artworkFile(cover, account)?.isFile == true
        }
        val fetched = java.util.concurrent.atomic.AtomicInteger(0)
        val failures = FailureRun()
        coroutineScope {
            // Measured against the home server: four parallel fetches manage
            // roughly seventy covers a minute and eight do no worse, while a
            // slow cover stalls its whole window, so a wider gate is the cheap
            // lever on a pass that runs to thousands of covers.
            val gate = Semaphore(ARTWORK_PARALLELISM)
            covers.forEach { cover ->
                launch {
                    gate.withPermit {
                        if (failures.exhausted()) return@withPermit
                        when (fetchArtwork(cover, account)) {
                            ArtworkOutcome.SAVED -> {
                                failures.success()
                                fetched.incrementAndGet()
                            }
                            // The server has no image: settle it so this pass and
                            // every later one skip it, and keep going.
                            ArtworkOutcome.ABSENT -> library.markArtworkAbsent(cover)
                            ArtworkOutcome.FAILED -> failures.failure()
                        }
                        notifications.updateArtwork(fetched.get(), covers.size)
                    }
                }
            }
        }
        if (failures.exhausted()) throw IOException("The network dropped while fetching artwork")
        return fetched.get()
    }

    fun scheduleArtworkPrefetch() {
        val account = library.account() ?: return
        val work = WorkManager.getInstance(context)
        work.enqueueUniqueWork("artwork_$account", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(workDataOf("account" to account, "artwork" to true))
                .build())
    }

    fun cancelArtworkPrefetch() {
        val account = library.account() ?: return
        WorkManager.getInstance(context).cancelUniqueWork("artwork_$account")
    }

    private fun hasUnmeteredWifi(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    suspend fun processQueue(account: String, wifiOnly: Boolean = false) {
        // Computed once: records() re-parses the whole store, so calling it per
        // song for progress would make a large fill quadratic in bookkeeping.
        var remaining = pendingTransferCount(library.records(account), wifiOnly)
        for (record in library.records(account).filter { it.wifiOnly == wifiOnly && it.error == null }) {
            if (library.account() != account) return
            // The Wi-Fi-only lane exists for metered connections. Stop the run
            // and let the work's network constraint bring it back; throwing here
            // failed the whole batch and pushed it into exponential backoff, so a
            // changed network state could park hours of transfers for nothing.
            if (wifiOnly && !hasUnmeteredWifi()) return
            if (library.record(record.key) != record) continue
            if (record.downloadId >= 0 || record.relativePath != null) {
                if (library.inspect(record).status == OfflineStatus.READY) mutex.withLock { cacheArtwork(record); cacheLyrics(record) }
                continue
            }
            try {
                if (storage.hasRoot()) {
                    if (spaceGuard(storage.freeBytes(), record.song.size)) {
                        // Stopping the run leaves the song queued, so WorkManager
                        // retries once space is freed instead of marking
                        // everything failed.
                        throw IOException("Waiting for free space on the storage folder")
                    }
                    notifications.update(record.song.title, remaining)
                    transferToStorage(record)
                    // Success or a recorded failure both clear the song from
                    // this run's pending set; a transport error throws instead.
                    remaining = (remaining - 1).coerceAtLeast(0)
                } else enqueue(record)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                when (classifyTransferFailure(error)) {
                    TransferFailure.RETRY_QUEUE -> throw error
                    TransferFailure.FAIL_RECORD ->
                        if (library.record(record.key) == record) {
                            library.replace(record, record.copy(error = downloadError(error)))
                        }
                }
            }
        }
        library.refresh()
    }

    private suspend fun enqueue(record: OfflineRecord) = withContext(Dispatchers.IO) {
        val song = record.song
        mutex.withLock {
            if (library.record(record.key) != record || library.account() != record.account) return@withContext
            val url = apiHelper.getDownloadUrl(song.id) ?: throw IOException("Sign in to download music.")
            val parsed = url.toHttpUrl()
            val key = downloadKey(parsed.newBuilder().query(null).build().toString(), parsed.queryParameter("u").orEmpty(), song.id)
            val previousId = preferences.getLong(key, -1)
            if (previousId != -1L) {
                manager.query(DownloadManager.Query().setFilterById(previousId))?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                            DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> {
                                library.replace(record, record.copy(downloadId = previousId))
                                return@withContext
                            }
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                val exists = try {
                                    manager.openDownloadedFile(previousId)?.use { true } ?: false
                                } catch (_: IOException) {
                                    false
                                }
                                if (exists) {
                                    library.replace(record, record.copy(downloadId = previousId))
                                    cacheArtwork(record)
                                    cacheLyrics(record)
                                    return@withContext
                                }
                            }
                        }
                    }
                }
                // A failed or deleted download can be retried, including its partial file.
                manager.remove(previousId)
                preferences.edit().remove(key).apply()
            }

            validateDownloadResponse(api.checkDownload(song.id))
            if (apiHelper.getDownloadUrl(song.id) != url) throw IOException("Your account changed. Try downloading again.")

            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(song.title)
                .setDescription(listOfNotNull(song.artist, song.album).joinToString(" • "))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, downloadPath(song, key))
            if (record.wifiOnly) request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
            synchronized(library) {
                if (library.record(record.key) != record || library.account() != record.account) return@withContext
                val id = manager.enqueue(request)
                preferences.edit().putLong(key, id).commit()
                library.save(record.copy(downloadId = id))
            }
            cacheArtwork(record)
            cacheLyrics(record)
        }
    }

    /**
     * Downloads through the picked storage root instead of DownloadManager.
     * The transfer runs outside the queue mutex so adding songs while a large
     * file is moving does not block the UI.
     */
    private suspend fun transferToStorage(record: OfflineRecord) = withContext(Dispatchers.IO) {
        val song = record.song
        val relativePath = downloadPath(song, record.key)
        try {
            if (library.account() != record.account) return@withContext
            if (library.record(record.key) != record) return@withContext
            if (storage.exists(relativePath)) {
                completeTransfer(record, relativePath)
                return@withContext
            }
            // No precheck call here, unlike the DownloadManager path: that
            // precheck exists because DownloadManager saves error pages as
            // files, while this transfer validates the actual response below
            // and costs the server only one request.
            val url = apiHelper.getDownloadUrl(song.id)?.toHttpUrl() ?: throw IOException("Sign in to download music.")
            val key = downloadKey(url.newBuilder().query(null).build().toString(), url.queryParameter("u").orEmpty(), song.id)
            if (key != record.key) throw IOException("Your account changed. Try downloading again.")
            // A slow server can legitimately take longer than the shared 30s read
            // timeout to start serving a file, so this call gets its own budget.
            val client = okHttp.newBuilder().readTimeout(3, TimeUnit.MINUTES).build()
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                val body = response.body ?: throw IOException("The server returned an empty download.")
                if (!response.isSuccessful) throw IOException("The server refused this download.")
                val type = body.contentType()
                if (type?.type == "text" || type?.subtype?.let { "json" in it || "xml" in it } == true ||
                    body.contentLength() == 0L
                ) throw IOException("The server refused this download. Check your account's download permission.")
                storage.write(relativePath) { output ->
                    body.byteStream().copyTo(output, 64 * 1024)
                }
            }
            completeTransfer(record, relativePath)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            when (classifyTransferFailure(error)) {
                TransferFailure.RETRY_QUEUE -> throw error
                TransferFailure.FAIL_RECORD ->
                    if (library.record(record.key) == record) {
                        library.replace(record, record.copy(error = downloadError(error)))
                    }
            }
        }
    }

    private fun downloadError(error: Exception) =
        "Could not download. Check connection, storage, and server permissions. " +
            "(${error.javaClass.simpleName}: ${error.message})"

    private suspend fun completeTransfer(record: OfflineRecord, relativePath: String) {
        if (library.replace(record, record.copy(relativePath = relativePath))) {
            cacheArtwork(record)
            cacheLyrics(record)
        }
    }

    private suspend fun cacheArtwork(record: OfflineRecord) {
        val cover = record.song.coverArt ?: return
        if (record.wifiOnly && !hasUnmeteredWifi()) return
        if (fetchArtwork(cover, record.account) == ArtworkOutcome.ABSENT) library.markArtworkAbsent(cover)
    }

    private suspend fun cacheLyrics(record: OfflineRecord) {
        if (record.wifiOnly && !hasUnmeteredWifi()) return
        val song = record.song
        try {
            var resolved = resolveLyrics(api.getLyricsBySongId(song.id).response.lyricsList?.structuredLyrics.orEmpty(), null)
            if (resolved == null) {
                resolved = resolveLyrics(emptyList(), api.getLyrics(song.artist, song.title).response.lyrics)
            }
            resolved?.let { lyricsCache.put(song.id, it) }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            // Lyrics are optional; a failed fetch leaves them uncached, and
            // the lyrics screen still fetches on view.
        }
    }

    /**
     * Fetches one cover into the permanent artwork store. Audio never depends on
     * it, so a failure is not fatal: the caller decides whether to try again or
     * to treat the cover as absent from the server for good.
     */
    internal suspend fun fetchArtwork(cover: String, account: String): ArtworkOutcome {
        if (library.account() != account) return ArtworkOutcome.FAILED
        val file = library.artworkFile(cover, account) ?: return ArtworkOutcome.FAILED
        if (file.exists()) return ArtworkOutcome.SAVED
        try {
            val result = context.imageLoader.execute(ImageRequest.Builder(context)
                .data(apiHelper.getCoverArtUrl(cover, 600)).size(600).allowHardware(false).build())
            if (result is SuccessResult && library.account() == account) {
                file.parentFile?.mkdirs()
                val temporary = java.io.File(file.path + ".tmp")
                temporary.outputStream().use { result.drawable.toBitmap().compress(Bitmap.CompressFormat.JPEG, 90, it) }
                temporary.renameTo(file)
                return ArtworkOutcome.SAVED
            }
            // A 404 is the server saying the image does not exist. Anything else
            // is a fetch that may work next time.
            if (result is ErrorResult && imageIsAbsent((result.throwable as? HttpException)?.response?.code)) {
                return ArtworkOutcome.ABSENT
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
        }
        return ArtworkOutcome.FAILED
    }

    fun setSyncFavorites(enabled: Boolean) {
        library.setFavoritesEnabled(enabled)
        val account = library.account() ?: return
        val work = WorkManager.getInstance(context)
        val name = "favorites_$account"
        if (!enabled) {
            work.cancelUniqueWork(name)
            work.cancelUniqueWork("${name}_now")
            return
        }
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
        val data = workDataOf("account" to account, "favorites" to true)
        work.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DownloadWorker>(6, TimeUnit.HOURS).setConstraints(constraints).setInputData(data).build())
        work.enqueueUniqueWork("${name}_now", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DownloadWorker>().setConstraints(constraints).setInputData(data).build())
    }

    /**
     * The record key for a song, derived exactly the way queue() derives it:
     * from the download URL without its query, the username, and the song id.
     * The manifest and the pre-seed script must reproduce this byte for byte.
     */
    private fun recordKey(song: SongItem): String? {
        val url = apiHelper.getDownloadUrl(song.id)?.toHttpUrl() ?: return null
        return downloadKey(url.newBuilder().query(null).build().toString(), url.queryParameter("u").orEmpty(), song.id)
    }

    /** Starts a full-library enumeration run; KEEP means a running scan is not replaced. */
    fun startLibraryDownload() {
        val account = library.account() ?: return
        WorkManager.getInstance(context).enqueueUniqueWork(
            "library_$account", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf("account" to account, "library" to true))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
        )
    }

    /** Stops the scan and the transfer run; already-queued songs keep their state. */
    fun cancelLibraryDownload() {
        val account = library.account() ?: return
        val work = WorkManager.getInstance(context)
        work.cancelUniqueWork("library_$account")
        work.cancelUniqueWork("downloads_${account}_false")
    }

    /**
     * Walks every album on the server and queues songs not yet saved. One
     * queue batch per album page keeps the transfer chain short. The walk
     * stops when the destination runs out of space; a transport failure of
     * the album list aborts the run for a worker retry.
     */
    fun startManifestExport() {
        val account = library.account() ?: return
        WorkManager.getInstance(context).enqueueUniqueWork(
            "export_$account", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf("account" to account, "export" to true))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
        )
    }

    fun startManifestImport() {
        val account = library.account() ?: return
        WorkManager.getInstance(context).enqueueUniqueWork(
            "import_$account", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf("account" to account, "import" to true))
                .build()
        )
    }

    fun cancelManifestExport() {
        val account = library.account() ?: return
        WorkManager.getInstance(context).cancelUniqueWork("export_$account")
    }

    fun cancelManifestImport() {
        val account = library.account() ?: return
        WorkManager.getInstance(context).cancelUniqueWork("import_$account")
    }

    /** True while the named unique work exists and has not finished. */
    fun workRunning(workName: String): kotlinx.coroutines.flow.Flow<Boolean> {
        val account = library.account() ?: return kotlinx.coroutines.flow.MutableStateFlow(false)
        val name = if (workName == "library") "library_$account" else "${workName}_$account"
        return WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(name)
            .map { infos -> infos.any { info -> !info.state.isFinished } }
    }

    /**
     * Writes the offline manifest into the picked storage folder: one JSON
     * line per song with the exact path the app expects and the song data,
     * so a computer can copy files into place and import needs no server.
     */
    suspend fun exportManifest(account: String): Int {
        if (library.account() != account) return 0
        val entries = mutableListOf<ManifestEntry>()
        val queued = enumerateLibrary(
            listAlbums = { offset ->
                val response = api.getAlbumList("alphabeticalByName", 200, offset).response
                if (!response.isOk) throw IOException("Could not list albums")
                response.albumList2?.album.orEmpty()
            },
            albumSongs = { albumId ->
                val response = api.getAlbum(albumId).response
                if (!response.isOk) throw IOException("Could not load album")
                response.album?.song.orEmpty()
            },
            queueSongs = { songs ->
                for (song in songs) {
                    val key = recordKey(song) ?: throw IOException("Sign in to export the file list.")
                    entries += ManifestEntry(downloadPath(song, key), song)
                }
                songs.size
            },
            keepGoing = { library.account() == account },
            onProgress = { _, exported -> notifications.updateExport(exported) }
        )
        storage.write(MANIFEST_NAME) { out ->
            out.bufferedWriter(Charsets.UTF_8).use { writer ->
                for (entry in entries) {
                    writer.write(manifestLine(entry))
                    writer.write("\n")
                }
            }
        }
        return queued
    }

    /**
     * Reads the manifest from the storage folder and marks every entry whose
     * file is present as ready. Works offline: the manifest carries the song
     * data, and the files are already on the storage. Missing files are
     * skipped, so a partial copy can be imported now and re-run later.
     */
    suspend fun importManifest(): Int = withContext(Dispatchers.IO) {
        val account = library.account() ?: throw IOException("Sign in to import saved files.")
        // A warm cache lists the storage as it was when the app last looked;
        // import must see files pushed since then, so start from a clean read.
        storage.dropStructureCache()
        val input = storage.openInput(MANIFEST_NAME)
            ?: throw IOException("No manifest file in the storage folder. Export it or copy one from your computer.")
        val entries = mutableListOf<ManifestEntry>()
        input.bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                parseManifestLine(line)?.let(entries::add)
            }
        }
        var imported = 0
        for (batch in entries.chunked(500)) {
            imported += importManifestEntries(
                batch,
                fileExists = { storage.exists(it) },
                recordFor = { entry ->
                    val key = recordKey(entry.song) ?: throw IOException("Sign in to import saved files.")
                    val updated = library.record(key)
                        ?.copy(song = entry.song, relativePath = entry.path, error = null)
                        ?: OfflineRecord(key, account, entry.song, relativePath = entry.path)
                    library.save(updated)
                }
            )
            notifications.updateImport(imported)
        }
        library.refresh()
        scheduleArtworkPrefetch()
        imported
    }

    suspend fun syncLibrary(account: String): Int {
        if (library.account() != account) return 0
        return enumerateLibrary(
            listAlbums = { offset ->
                val response = api.getAlbumList("alphabeticalByName", 200, offset).response
                if (!response.isOk) throw IOException("Could not list albums")
                response.albumList2?.album.orEmpty()
            },
            albumSongs = { albumId ->
                val response = api.getAlbum(albumId).response
                if (!response.isOk) throw IOException("Could not load album")
                response.album?.song.orEmpty()
            },
            // Wi-Fi only: a bulk fill must never start eating metered data the
            // moment the tablet connects to a phone hotspot. Scanning is cheap;
            // the transfers are not.
            queueSongs = { songs -> queue(songs, wifiOnly = true) },
            keepGoing = { library.account() == account && !spaceGuard(storage.freeBytes(), null) },
            onProgress = { scanned, queued -> notifications.updateLibrary(scanned, queued) }
        )
    }

    suspend fun syncFavorites(account: String) {
        if (library.account() != account || !library.favoritesEnabled(account)) return
        if (!hasUnmeteredWifi()) throw IOException("Waiting for unmetered Wi-Fi")
        val generation = auth.generation.value
        val credentials = auth.getCachedCredentials()
        val response = api.getStarred().response
        if (!response.isOk) throw IOException("Could not load favorites")
        if (generation != auth.generation.value || credentials != auth.getCachedCredentials() || !library.favoritesEnabled(account)) return
        queue(response.starred2?.song.orEmpty(), wifiOnly = true)
    }
}

/** Covers fetched at once during a pass. */
internal const val ARTWORK_PARALLELISM = 8
