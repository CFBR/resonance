package com.resonance.music.ui.screens.lyrics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.resonance.music.data.repository.CachedLyrics
import com.resonance.music.data.repository.LyricsCache
import com.resonance.music.data.repository.MusicRepository
import com.resonance.music.data.repository.resolveLyrics
import com.resonance.music.playback.PlaybackManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LyricsLine(
    val startMs: Long,
    val text: String
)

data class LyricsUiState(
    val isLoading: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val coverArtUrl: String? = null,
    val plainLyrics: String? = null,
    val lines: List<LyricsLine> = emptyList(),
    val isSynced: Boolean = false,
    val currentLineIndex: Int = -1
)

@HiltViewModel
class LyricsViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val lyricsCache: LyricsCache,
    private val playbackManager: PlaybackManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(LyricsUiState())
    val uiState: StateFlow<LyricsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            playbackManager.nowPlaying.map { it.song }.distinctUntilChangedBy { it?.id }.collectLatest { song ->
                if (song == null) {
                    _uiState.value = LyricsUiState()
                } else {
                    _uiState.value = LyricsUiState(
                        title = song.title,
                        artist = song.artist ?: "",
                        coverArtUrl = song.coverArt?.let { musicRepository.getCoverArtUrl(it) }
                    )
                    loadLyrics(song.id, song.artist, song.title)
                }
            }
        }
    }

    private suspend fun loadLyrics(songId: String, artist: String?, title: String) {
        // Cached lyrics show immediately; the live fetch then refreshes them.
        val cached = lyricsCache.get(songId)
        if (cached != null) show(cached)
        _uiState.value = _uiState.value.copy(isLoading = cached == null)
        try {
            var resolved = resolveLyrics(musicRepository.getStructuredLyrics(songId), null)
            if (resolved == null) {
                resolved = resolveLyrics(emptyList(), musicRepository.getLyrics(artist, title))
            }
            resolved?.let { lyricsCache.put(songId, it) }
            if (resolved != null) {
                show(resolved)
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    plainLyrics = null,
                    lines = emptyList(),
                    isSynced = false,
                    currentLineIndex = -1
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // No connection and nothing cached: the screen reports that.
            // With a cache, its contents stand.
            if (cached == null) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    plainLyrics = null,
                    lines = emptyList(),
                    isSynced = false,
                    currentLineIndex = -1
                )
            } else {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    private fun show(lyrics: CachedLyrics) {
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            lines = if (lyrics.synced) lyrics.startMs.zip(lyrics.text).map { LyricsLine(it.first, it.second) } else emptyList(),
            plainLyrics = if (lyrics.synced) null else lyrics.text.joinToString("\n"),
            isSynced = lyrics.synced,
            currentLineIndex = -1
        )
    }

    fun seekTo(positionMs: Long) {
        playbackManager.seekTo(positionMs)
    }

    fun updateCurrentPosition() {
        if (!_uiState.value.isSynced) return
        val lines = _uiState.value.lines
        if (lines.isEmpty()) return

        val position = playbackManager.getCurrentPosition()
        val index = lines.indexOfLast { it.startMs <= position }
        if (index != _uiState.value.currentLineIndex) {
            _uiState.value = _uiState.value.copy(currentLineIndex = index)
        }
    }
}
