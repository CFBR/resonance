package com.resonance.music.ui.screens.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.resonance.music.data.download.*
import com.resonance.music.data.repository.MusicRepository
import com.resonance.music.playback.PlaybackManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    val totals: StateFlow<OfflineTotals> = tracks
        .map(::offlineTotals)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OfflineTotals(0, 0, 0))
    val libraryRunning: StateFlow<Boolean> = downloader.workRunning("library")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val exportRunning: StateFlow<Boolean> = downloader.workRunning("export")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val importRunning: StateFlow<Boolean> = downloader.workRunning("import")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setSyncFavorites(enabled: Boolean) = downloader.setSyncFavorites(enabled)
    fun startLibraryDownload() = downloader.startLibraryDownload()
    fun cancelLibraryDownload() = downloader.cancelLibraryDownload()
    fun startManifestExport() = downloader.startManifestExport()
    fun cancelManifestExport() = downloader.cancelManifestExport()
    fun startManifestImport() = downloader.startManifestImport()
    fun cancelManifestImport() = downloader.cancelManifestImport()
    fun coverArt(id: String) = music.getCoverArtUrl(id)
    fun play(songId: String? = null, shuffle: Boolean = false) {
        val songs = tracks.value.filter { it.status == OfflineStatus.READY }.map { it.record.song }
        if (songs.isEmpty()) return
        playback.playSongs(if (shuffle) songs.shuffled() else songs, songs.indexOfFirst { it.id == songId }.coerceAtLeast(0))
    }
    fun remove(track: OfflineTrack) { viewModelScope.launch { library.remove(track) } }
}
