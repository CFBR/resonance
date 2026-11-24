package com.resonance.music.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionCommand
import com.resonance.music.data.api.models.SongItem
import com.resonance.music.data.download.OfflineLibrary
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class NowPlaying(
    val song: SongItem? = null,
    /** Playback is wanted, including while buffering: the pause button is showing. */
    val isPlaying: Boolean = false,
    val duration: Long = 0L
)

enum class RepeatMode { OFF, ALL, ONE }

/**
 * UI-facing playback API. Connects a Media3 [MediaController] to [PlaybackService]
 * and exposes playback state as flows. Playback position is a separate flow from
 * [nowPlaying] so position ticks don't recompose song/controls.
 */
@Singleton
class PlaybackManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaItems: MediaItems,
    private val offline: OfflineLibrary,
    private val queueStore: QueueStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var controller: MediaController? = null
    private var connecting = false

    private val _nowPlaying = MutableStateFlow(NowPlaying())
    val nowPlaying: StateFlow<NowPlaying> = _nowPlaying.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _queue = MutableStateFlow<List<SongItem>>(emptyList())
    val queue: StateFlow<List<SongItem>> = _queue.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private var pendingPlay: Pair<List<SongItem>, Int>? = null
    private var positionJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) startPositionUpdates() else stopPositionUpdates()
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED
                )
            ) {
                _nowPlaying.update { it.copy(isPlaying = !Util.shouldShowPlayButton(player)) }
            }
            if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_METADATA_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                _queue.value = (0 until player.mediaItemCount).map { MediaItems.song(player.getMediaItemAt(it)) }
                syncNowPlaying()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncNowPlaying()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) syncNowPlaying()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _shuffleEnabled.value = shuffleModeEnabled
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _repeatMode.value = repeatMode.toAppRepeatMode()
        }
    }

    /** Connects the UI-side controller to the playback service. Idempotent. */
    fun initialize() {
        if (controller != null || connecting) return
        connecting = true
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            connecting = false
            val c = try {
                future.get()
            } catch (e: Exception) {
                Log.e("PlaybackManager", "Failed to connect MediaController", e)
                return@addListener
            }
            controller = c
            c.addListener(listener)
            onControllerReady(c)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun onControllerReady(c: MediaController) {
        _shuffleEnabled.value = c.shuffleModeEnabled
        _repeatMode.value = c.repeatMode.toAppRepeatMode()

        val pending = pendingPlay
        if (pending != null) {
            pendingPlay = null
            startPlayback(c, pending.first, pending.second)
            return
        }

        // App was restarted while the service kept playing: rebuild the queue
        // from the session's media items so the mini player / queue stay populated.
        if (_queue.value.isEmpty() && c.mediaItemCount > 0) {
            _queue.value = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).toSongItem() }
            rememberQueue()
        } else if (_queue.value.isEmpty() && c.mediaItemCount == 0) {
            // Nothing playing: put back what the user had lined up before the
            // process died, paused, so reopening the app starts no audio.
            restoreQueue(c)
        }
        syncNowPlaying()
        if (c.isPlaying) startPositionUpdates()
    }

    /**
     * Puts the saved queue back, paused. Items are built off the main thread
     * because an "everything, shuffled" queue runs to thousands of songs, and
     * nothing is prepared: reopening the app must not spend data on a stream.
     */
    private fun restoreQueue(c: MediaController) {
        scope.launch {
            // Reading the saved queue is a few megabytes of JSON, so it happens
            // off the main thread along with building the items.
            val built = withContext(Dispatchers.Default) {
                // The credentials are read from a store at startup, so the
                // account can still be unknown this early. Wait for it rather
                // than dropping the queue on the floor.
                var account = offline.account()
                var waited = 0L
                while (account == null && waited < ACCOUNT_WAIT_MS) {
                    delay(100)
                    waited += 100
                    account = offline.account()
                }
                account ?: return@withContext null
                val snapshot = queueStore.snapshot()
                if (snapshot == null || snapshot.account != account) return@withContext null
                val place = queueStore.place()
                builtQueue(snapshot.songs, place?.index ?: 0) { buildMediaItem(it) } to place
            } ?: return@launch
            val (queue, place) = built
            if (queue.items.isEmpty()) return@launch
            Log.d("PlaybackManager", "Restored ${queue.items.size} queued songs")
            _queue.value = queue.songs
            c.setMediaItems(queue.items, queue.startIndex, place?.positionMs ?: 0L)
            syncNowPlaying()
        }
    }

    /** Remembers the queue and where playback is, for the next process. */
    private fun rememberQueue() {
        val account = offline.account() ?: return
        val songs = _queue.value
        val place = controller?.let { QueuePlace(it.currentMediaItemIndex, it.currentPosition) }
        scope.launch(Dispatchers.IO) {
            queueStore.saveSnapshot(if (songs.isEmpty()) null else QueueSnapshot(account, songs))
            queueStore.savePlace(if (songs.isEmpty()) null else place)
        }
    }

    private fun syncNowPlaying() {
        val c = controller ?: return
        val song = _queue.value.getOrNull(c.currentMediaItemIndex)
        _position.value = c.currentPosition
        _nowPlaying.value = NowPlaying(
            song = song,
            isPlaying = !Util.shouldShowPlayButton(c),
            duration = if (c.duration > 0) c.duration else (song?.duration?.toLong() ?: 0L) * 1000
        )

    }

    fun playSongs(songs: List<SongItem>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        initialize()
        val c = controller
        if (c != null) {
            startPlayback(c, songs, startIndex)
        } else {
            pendingPlay = songs to startIndex
            _queue.value = songs
            songs.getOrNull(startIndex.coerceIn(0, songs.size - 1))?.let {
                _nowPlaying.value = NowPlaying(
                    song = it,
                    isPlaying = false,
                    duration = (it.duration?.toLong() ?: 0L) * 1000
                )
            }
        }
    }

    private fun startPlayback(c: MediaController, songs: List<SongItem>, startIndex: Int) {
        scope.launch {
            // Building thousands of media items serializes every song; keep
            // that off the main thread even though each one is cheap now.
            val built = withContext(Dispatchers.Default) {
                builtQueue(songs, startIndex) { buildMediaItem(it) }
            }
            if (built.items.isEmpty()) return@launch
            _queue.value = built.songs
            c.setMediaItems(built.items, built.startIndex, 0L)
            rememberQueue()
            c.prepare()
            c.play()
            syncNowPlaying()
        }
    }

    /** Build media items and the matching song list together so [_queue] never
     *  drifts from the controller timeline when a song cannot play. */
    private fun buildItemsAndSongs(songs: List<SongItem>): Pair<List<MediaItem>, List<SongItem>> {
        val built = builtQueue(songs, 0) { buildMediaItem(it) }
        return built.items to built.songs
    }

    /** Seek to a queue position without rebuilding the queue (fast in-place jump). */
    fun jumpTo(index: Int) {
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount) {
            c.seekTo(index, 0L)
            c.play()
        }
    }

    /** Insert songs immediately after the current track. Starts a new queue if nothing is playing. */
    fun playNext(songs: List<SongItem>) {
        if (songs.isEmpty()) return
        val c = controller
        if (c == null || c.mediaItemCount == 0) { playSongs(songs); return }
        val (items, kept) = buildItemsAndSongs(songs)
        if (items.isEmpty()) return
        val insertIndex = (c.currentMediaItemIndex + 1).coerceAtMost(c.mediaItemCount)
        c.addMediaItems(insertIndex, items)
        _queue.update { queue -> queue.toMutableList().apply { addAll(insertIndex, kept) } }
        rememberQueue()
    }

    /** Append songs to the end of the queue. Starts a new queue if nothing is playing. */
    fun addToQueue(songs: List<SongItem>) {
        if (songs.isEmpty()) return
        val c = controller
        if (c == null || c.mediaItemCount == 0) { playSongs(songs); return }
        val (items, kept) = buildItemsAndSongs(songs)
        if (items.isEmpty()) return
        c.addMediaItems(c.mediaItemCount, items)
        _queue.update { queue -> queue + kept }
        rememberQueue()
    }

    /** Remove the queue entry at [index]. If it is the current track, Media3 advances to the next. */
    fun removeFromQueue(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        c.removeMediaItem(index)
        _queue.update { queue -> queue.toMutableList().apply { removeAt(index.coerceIn(0, lastIndex)) } }
        rememberQueue()
        syncNowPlaying()
    }

    /** Move a queue entry from one position to another (drag-to-reorder). */
    fun moveQueueItem(from: Int, to: Int) {
        val c = controller ?: return
        if (from !in 0 until c.mediaItemCount || to !in 0 until c.mediaItemCount || from == to) return
        c.moveMediaItem(from, to)
        _queue.update { queue -> queue.toMutableList().apply { add(to, removeAt(from)) } }
        rememberQueue()
        syncNowPlaying()
    }

    /** Clear everything and stop playback. */
    fun clearQueue() {
        val c = controller ?: return
        c.clearMediaItems()
        _queue.value = emptyList()
        rememberQueue()
        syncNowPlaying()
    }

    /** Decides by playWhenReady, not isPlaying (false while buffering, so the button did
     *  nothing). After an error or the end of the queue it re-prepares or rewinds first. */
    fun togglePlayPause() {
        val c = controller ?: return
        Util.handlePlayPauseButtonAction(c)
    }

    fun next() {
        controller?.takeIf { it.hasNextMediaItem() }?.seekToNextMediaItem()
    }

    fun previous() {
        val c = controller ?: return
        if (c.currentPosition > 3000) {
            c.seekTo(0)
        } else if (c.hasPreviousMediaItem()) {
            c.seekToPreviousMediaItem()
        }
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
        _position.value = positionMs
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    fun toggleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun toggleFavorite() {
        controller?.sendCustomCommand(SessionCommand(LibrarySessionCallback.FAVORITE, Bundle.EMPTY), Bundle.EMPTY)
    }

    fun getCurrentPosition(): Long = controller?.currentPosition ?: _position.value

    private fun startPositionUpdates() {
        positionJob?.cancel()
        positionJob = scope.launch {
            while (isActive) {
                controller?.let { _position.value = it.currentPosition }
                delay(500)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
    }

    private fun buildMediaItem(song: SongItem): MediaItem? {
        return mediaItems.build(song)
    }

    private fun MediaItem.toSongItem(): SongItem {
        return MediaItems.song(this)
    }

    private fun Int.toAppRepeatMode(): RepeatMode = when (this) {
        Player.REPEAT_MODE_ONE -> RepeatMode.ONE
        Player.REPEAT_MODE_ALL -> RepeatMode.ALL
        else -> RepeatMode.OFF
    }

}

private const val PLACE_SAVE_MS = 10_000L

/** How long to wait for the stored credentials before giving up on a restore. */
private const val ACCOUNT_WAIT_MS = 3_000L
