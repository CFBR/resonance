# Changelog

All notable changes to Resonance will be documented in this file.

## [Unreleased]

### Added

- A one-pass artwork fetch downloads a cover for every saved song that lacks
  one, so the offline library browses with complete artwork. It runs on
  Wi-Fi only, survives interruption by picking up where it left off, starts
  automatically after an import, and can also be started from the Downloads
  screen.

- The library tabs and search now work without a connection: when the server
  cannot be reached, artists, albums, genres, favorites, and search results
  come from the songs saved on the device. Only a connectivity failure takes
  the fallback; a server that answers with an error still surfaces the error.
  Playlists cannot be derived from saved songs and need the server.

### Added

- An offline storage root (typically the SD card) picked with the system folder
  picker. When one is set, downloads run in-app into that folder in original
  quality instead of going to internal storage through DownloadManager.
- The settings screen shows the chosen folder and can reset to internal storage.

### Fixed

- Stage part files as generic bytes: providers append the MIME's canonical
  extension to names that lack it, which turned staged "<name>.part" files into
  untrackable "<name>.part.mp3" files and broke downloads on some devices.
- Give the download transfer its own read timeout budget, so a slow server can
  take longer than the shared 30-second limit to start serving a file.
- Record the actual failure cause on a download error instead of a generic
  message.

### Added

- A foreground download queue: a progress notification with songs remaining and
  the current file, exponential backoff on transport errors, and resume from the
  interrupted song after the app is killed mid-fill. Transfers continue with the
  screen off.

### Added

- "Download entire library": scans every album on the server, queues missing
  songs, and shows scan progress in a notification alongside the transfer
  progress. Transfers wait for Wi-Fi, so a bulk fill never consumes metered
  data, and a Stop button cancels the run while keeping what is already saved.
  The Downloads screen shows how many songs are saved, waiting, or failed.

### Added

- "Fill from a computer": export the expected file layout as a manifest
  (resonance-manifest.jsonl) into the storage folder, copy files into place over
  USB with the bundled pre-seed script, then import them as ready offline
  records with no download traffic. The manifest carries each song's metadata,
  so importing works fully offline. A new tools/preseed script mirrors the app's
  path and key computation for computers doing the copying.

### Fixed

- Plays that happen without a connection are no longer lost: failed scrobbles
  wait in a persistent queue and reach the server with their original listen
  time once the network returns.

- Stop the queue and retry later when the storage folder is nearly full, instead
  of marking every song as failed.
- A failed children listing no longer poisons the folder cache into rejecting
  later downloads of that album.

## [0.8.3] - 2026-11-09

### Fixed

- Trust CA certificates installed in Android's user certificate store when connecting to a music server.

## [0.8.2] - 2026-11-08

### Fixed

- Keep the lyrics screen open when the server is unavailable and clear previous lyrics when changing tracks.
- Stop playback and show an error when a stream redirects to an unsupported URL, preserving the selected track.

## [0.8.1] - 2026-10-27

### Fixed

- Keep bottom navigation labels on one line at narrow widths and larger text sizes, with an ellipsis when space is limited.

## [0.8.0] - 2026-10-26

### Added

- In-app offline playback with a Downloads library, saved artwork, progress, retry, removal, and account isolation.
- Album, playlist, and artist downloads, plus optional Wi-Fi syncing of favorite songs.
- Android Auto discovery, library browsing, search, and playback through a shared MediaLibraryService.
- System media-card artwork, favorite and repeat actions, and reserved previous/next controls.

### Fixed

- Synchronize the phone's queue and favorite state with external media controllers.
- Report playback from the service so scrobbling also works when a car starts playback.

## [0.7.0] - 2026-09-25

### Added

- Download a song from its menu to `Music/Resonance/Artist/Album`, preserving the original format, with Android download notifications and duplicate detection.

### Fixed

- Keep playback loading with the screen off and retry temporary connection failures without skipping through the queue.
- Make play/pause work while buffering and allow playback to restart after an error or the end of the queue.
- Seek once when releasing the progress slider instead of restarting the stream on every drag movement.
- Use mirrored Compose icons for direction-sensitive song, album, library, and playlist actions.

## [0.6.1] - 2026-06-13

### Changed
- Release builds are now reproducible: pinned R8 to a deterministic version and disabled the non-reproducible baseline profile, so a build from source can be verified against the published APK (for F-Droid)

## [0.6.0] - 2026-06-13

### Added
- Play next and add to queue, from a song's menu or for a whole album or playlist
- Editable play queue: drag to reorder, swipe to remove, and clear
- Artist pages now have a Tracks tab alongside Albums, plus play, shuffle, and loop
- Tap an artist or album name on the album screen or the player to navigate to it

### Changed
- "Play next" now inserts after the current track instead of playing immediately

## [0.5.2] - 2026-06-12

### Changed
- Slimmer top bar and a more compact bottom navigation, leaving more room for content

## [0.5.1] - 2026-06-12

### Fixed
- Pull-to-refresh indicator no longer lingers at the top of Home and Library when idle
- Lyrics screen shows "No lyrics available" instead of a blank page when a track has no lyrics

## [0.5.0] - 2026-06-12

### Added
- Favorite (star) toggle on the album and artist screens
- Artist detail: biography and a similar-artists strip
- Genres tab with a per-genre album grid
- Album library is now a grid with sort options (A–Z, recently added, recently played, most played, random)
- Multi-disc albums are grouped under "Disc N" headers
- Tap a synced lyric line to seek to it
- Pull down to refresh the home feed and the current library tab
- Player gestures: swipe the mini-player up to expand, swipe the full player down to minimize, swipe the album art left/right to change tracks

### Changed
- Search and Settings are now bottom-navigation tabs alongside Home and Library
- The full player opens as a slide-up screen over the bottom bar
- Removed the empty bands at the top and bottom of screens under edge-to-edge layout
- Stored credentials are excluded from cloud backup and device transfer
- F-Droid: omit AGP's DependencyInfoBlock from the APK

## [0.4.0] - 2026-06-12

### Added
- Redesigned home screen with a "jump back in" hero and a shuffle-all action
- New typography using the bundled Space Grotesk and Inter fonts
- New ripple launcher icon with a themed (monochrome) variant
- Redesigned login screen

### Changed
- Rebuilt the UI/playback link on a Media3 `MediaController`; playback position is now a separate flow, so the UI no longer recomposes on every position tick
- Shared, larger image cache and right-sized cover-art requests for faster scrolling
- Release builds now build from source without signing secrets (F-Droid friendly)

### Fixed
- Re-login or server change now takes effect without restarting the app
- Scrobbling now reports plays to the server
- Album art now loads in search results
- Favorite toggle reverts if the server request fails

### Removed
- Local song downloads and the unused on-device cache layer

## [0.3.1] - 2026-04-05

### Changed
- Hardened `PlaybackService` export with `BIND_MEDIA_SESSION_SERVICE`
- Disabled OkHttp HTTP logging in non-debuggable builds
- Refreshed launcher icon foreground

### Fixed
- Reduced playback error loops by pausing after repeated failures
- Improved Compose list performance via better `contentType` hints

### Added
- `signing.properties.example` template for local signing setup

## [0.1.0] - 2025-03-24

### Added
- Initial release
- Subsonic/Navidrome API integration (v1.16.1)
- Music streaming with ExoPlayer / Media3
- Background playback with foreground service and media notifications
- Browse by artists, albums, playlists, and favorites
- Full-text search across artists, albums, and songs
- Full player screen with shuffle, repeat, seek, and favorite controls
- Synced and plain-text lyrics display
- Offline song downloads with progress tracking
- Multi-theme support (Neon Pulse, Aurora, Midnight Ocean, Solar Flare)
- Mini player with playback controls on all screens
- Scrobble reporting
- Gapless playback option
- Cover art loading with caching

### Fixed
- Playback crashes from threading issues (ExoPlayer operations on wrong thread)
- Character encoding issues with non-ASCII usernames and passwords
- ForegroundServiceDidNotStartInTimeException under rapid interactions
- Mini player state lost when app is backgrounded and restored
- Memory leak in position update handler
- ANR risk from blocking calls during dependency injection
