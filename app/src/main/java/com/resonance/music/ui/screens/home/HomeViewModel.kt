package com.resonance.music.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.resonance.music.data.api.models.AlbumItem
import com.resonance.music.data.repository.MusicRepository
import com.resonance.music.playback.PlaybackManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val isLoading: Boolean = false,
    val recentAlbums: List<AlbumItem> = emptyList(),
    val newestAlbums: List<AlbumItem> = emptyList(),
    val frequentAlbums: List<AlbumItem> = emptyList(),
    val randomAlbums: List<AlbumItem> = emptyList(),
    val savedAlbums: List<AlbumItem> = emptyList(),
    /** The shelves came from the saved records, not the server's play history. */
    val fromSavedAlbums: Boolean = false,
    val error: String? = null
) {
    // First album with art — used for the "jump back in" hero. Offline the
    // shelves are the saved copy, which cannot say what was played last.
    val featured: AlbumItem?
        get() = if (fromSavedAlbums) null else
            (recentAlbums + newestAlbums).firstOrNull { it.coverArt != null }
                ?: recentAlbums.firstOrNull()
                ?: newestAlbums.firstOrNull()
}

/** One shelf as the screen shows it. */
internal data class HomeShelf(val key: String, val title: String, val albums: List<AlbumItem>)

/**
 * The shelves Home offers. With no server, Recently played, Newest additions,
 * and Most played cannot be backed by anything the saved copy knows, so they
 * give way to one shelf that says what it is. Random picks and Shuffle all
 * still work, because a shuffle over the saved songs is exactly what a shuffle
 * offline means.
 */
internal fun homeShelves(state: HomeUiState): List<HomeShelf> =
    if (state.fromSavedAlbums) listOf(
        HomeShelf("saved", "Saved albums", state.savedAlbums),
        HomeShelf("random", "Random picks", state.randomAlbums)
    ) else listOf(
        HomeShelf("recent", "Recently played", state.recentAlbums),
        HomeShelf("newest", "Newest additions", state.newestAlbums),
        HomeShelf("frequent", "Most played", state.frequentAlbums),
        HomeShelf("random", "Random picks", state.randomAlbums)
    )

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val playbackManager: PlaybackManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun loadHome() {
        if (_uiState.value.isLoading) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                _uiState.value = fetchHome()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.localizedMessage ?: "Connection failed"
                )
            }
        }
    }

    /**
     * Re-fetch for pull-to-refresh. Suspends until done so the caller can stop
     * the refresh indicator, and never flips isLoading — the list stays on
     * screen with the pull spinner over it instead of a blank loading state.
     */
    suspend fun refresh() {
        try {
            _uiState.value = fetchHome()
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(error = e.localizedMessage ?: "Connection failed")
        }
    }

    private suspend fun fetchHome(): HomeUiState {
        val shelves = musicRepository.getHomeShelves(10)
        return HomeUiState(
            recentAlbums = shelves.recent,
            newestAlbums = shelves.newest,
            frequentAlbums = shelves.frequent,
            randomAlbums = shelves.random,
            savedAlbums = shelves.saved,
            fromSavedAlbums = shelves.fromSavedAlbums
        )
    }

    /** Play a batch of random songs from the whole library. */
    fun shuffleAll() {
        viewModelScope.launch {
            val songs = runCatching { musicRepository.getRandomSongs(50) }.getOrDefault(emptyList())
            if (songs.isNotEmpty()) playbackManager.playSongs(songs)
        }
    }

    /** Fetch an album's tracks and start playing it. */
    fun playAlbum(albumId: String) {
        viewModelScope.launch {
            val songs = runCatching { musicRepository.getAlbumDetail(albumId)?.song }.getOrNull().orEmpty()
            if (songs.isNotEmpty()) playbackManager.playSongs(songs)
        }
    }

    fun getCoverArtUrl(coverArtId: String, size: Int = 320): String? =
        musicRepository.getCoverArtUrl(coverArtId, size)
}
