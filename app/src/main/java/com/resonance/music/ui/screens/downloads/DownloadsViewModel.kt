package com.resonance.music.ui.screens.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.resonance.music.data.download.*
import com.resonance.music.data.repository.MusicRepository
import com.resonance.music.playback.PlaybackManager
import kotlinx.coroutines.Dispatchers
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val library: OfflineLibrary,
    private val downloader: SongDownloader,
    private val playback: PlaybackManager,
    private val music: MusicRepository
) : ViewModel() {
    val tracks = library.tracks
    val syncFavorites = library.syncFavorites
    /** Search text and status bucket for the management console. */
    val query = MutableStateFlow("")
    val statusFilter = MutableStateFlow(DownloadFilter.ALL)
    val groups: StateFlow<List<DownloadGroup>> = combine(tracks, query, statusFilter) { t, q, f ->
        consoleGroups(t, q, f)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val totals: StateFlow<OfflineTotals> = tracks
        .map(::offlineTotals)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OfflineTotals(0, 0, 0))
    val libraryRunning: StateFlow<Boolean> = downloader.workRunning("library")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val exportRunning: StateFlow<Boolean> = downloader.workRunning("export")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val importRunning: StateFlow<Boolean> = downloader.workRunning("import")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val artworkRunning: StateFlow<Boolean> = downloader.workRunning("artwork")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val heartbeat: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(5_000)
        }
    }

    /**
     * How much of the artwork the offline library still lacks. Recounted on a
     * slow beat as well as on change, because a pass takes hours and the row
     * has to move while it runs; equal values do not recompose, so an idle
     * screen costs nothing.
     */
    val artwork: StateFlow<ArtworkSummary?> = combine(tracks, heartbeat) { t, _ -> t }
        .map { all ->
            artworkSummary(all.filter { it.status == OfflineStatus.READY }.map { it.record.song }) { cover ->
                library.artworkFile(cover)?.isFile == true
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setSyncFavorites(enabled: Boolean) = downloader.setSyncFavorites(enabled)
    fun setQuery(text: String) { query.value = text }
    fun setStatusFilter(filter: DownloadFilter) { statusFilter.value = filter }
    fun startLibraryDownload() = downloader.startLibraryDownload()
    fun cancelLibraryDownload() = downloader.cancelLibraryDownload()
    fun startManifestExport() = downloader.startManifestExport()
    fun cancelManifestExport() = downloader.cancelManifestExport()
    fun startManifestImport() = downloader.startManifestImport()
    fun cancelManifestImport() = downloader.cancelManifestImport()
    fun startArtworkPrefetch() = downloader.scheduleArtworkPrefetch()
    fun cancelArtworkPrefetch() = downloader.cancelArtworkPrefetch()
    fun coverArt(id: String) = music.getCoverArtUrl(id)
    fun play(songId: String? = null, shuffle: Boolean = false) {
        val songs = tracks.value.filter { it.status == OfflineStatus.READY }.map { it.record.song }
        if (songs.isEmpty()) return
        playback.playSongs(if (shuffle) songs.shuffled() else songs, songs.indexOfFirst { it.id == songId }.coerceAtLeast(0))
    }
    fun remove(track: OfflineTrack) { viewModelScope.launch { library.remove(track) } }
    fun removeGroup(group: DownloadGroup) { viewModelScope.launch { group.songs.forEach { library.remove(it) } } }
}
