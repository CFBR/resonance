# Changelog

All notable changes to Resonance will be documented in this file.

## [Unreleased]

### Added

- A tunnel left over after Wi-Fi drops no longer counts as a connection.
  Android keeps the VPN as the active network with its default routes after
  the network under it goes away, so the app believed it was online and
  waited out a full timeout on every read, sometimes on a blank screen. The
  network check now asks whether any visible network is a real transport,
  with the VPN set aside, and losing a network counts as evidence the server
  is out of reach straight away.

- Home and Library say when they are showing the saved copy, since the
  saved copy and the live library look identical otherwise. The line hides
  itself once the server answers, and the health gate now holds a dead
  server closed while the app keeps reading, instead of spending another
  connect attempt every twenty seconds; it still tries again within two
  minutes, so a recovered server is noticed without a restart.

- Files already on the storage are no longer reported missing when their
  folder names differ only in letter case. A card is exFAT and is
  case-insensitive, so a folder listed as "Alive Or Just Breathing" still
  holds the file a manifest calls "Alive or Just Breathing"; comparing
  names exactly made those files invisible, so Import skipped them and
  the queue offered to download duplicates. The storage walk now matches
  a name exactly first and ignoring case second, and only when that
  match is unambiguous. On the test tablet this brought the whole
  manifest in: 8,890 songs saved became 9,445, with 318 stuck records
  and 14 failed ones resolved without transferring anything.

- Offline views reach the saved copy much faster. The first screen opened
  with no reachable server still waits one connection attempt, now limited
  to ten seconds instead of thirty; after that failure the app remembers it
  for twenty seconds, so the next screens read the saved records
  immediately instead of each waiting out the same timeout. A network
  change, or any request that succeeds, forgets the failure right away.
  A hotspot login page that answers instead of the server also counts as
  unreachable, so the saved copy still appears.

- Home stops claiming a play history it does not have offline. The three
  sorted shelves used to show the saved albums in name order under
  "Recently played", "Newest additions", and "Most played". With no
  connection they give way to one "Saved albums" shelf, the "jump back in"
  hero is dropped, and Random picks keeps working. The four shelves now
  load as one parallel read instead of four sequential ones, so the
  offline switch lands in the same time it took before.

- Shuffle all now works with no connection. It asked the server for a
  random pick and swallowed the failure, so the landing screen's main
  button did nothing offline; it now shuffles the saved songs instead.
  The Random picks shelf gets the same treatment, so it is shuffled rather
  than another copy of the name-sorted list the other shelves show.

- Artist pages keep their introduction offline. The biography and
  similar-artists strip come from a separate server call that had no
  fallback, so an artist page with no connection was a bare album list.
  What the server returns for an artist is now kept, and a failed fetch
  serves that copy.

- Playlists now open with no connection. The app keeps the list and the
  songs of any playlist opened while connected, so the playlists tab and
  a saved playlist's songs render offline instead of failing. The server
  stays the source of truth: an offline session can read playlists but not
  change them, a playlist edited elsewhere shows its cached songs until
  the next online visit, and a playlist never opened shows a line saying
  to open it once while connected rather than an error.

- Instrumented storage tests no longer leave the installed app without a
  storage folder. They pointed the app at a fake provider and cleared the
  setting afterwards, so a device that ran the tests showed an empty
  savings list until someone picked the folder again. The tests now read
  the stored folder before they touch it and put it back, and skip the
  restore entirely if setup never got that far.

- Import now finds files a computer pushed while the app was running. It
  used to keep the directory listings it read at startup, so a push landed
  behind a stale cache and import marked almost nothing ready; the app
  carried a "force-stop first" workaround for that. The import drops the
  cached structure before it checks for files, since it is the one
  operation that must see changes made outside the app.

- The downloads list is now a management console instead of an
  8,890-row wall. A search box finds a song by title, artist, or album;
  status chips split saved, waiting, and failed; and rows group under
  their album, with one remove button that clears the whole album after
  a count-first confirmation. The grouping, filtering, and ordering live
  in one pure mapping with tests, computed off the main thread.

- Shuffling a full downloads list no longer freezes the screen for about a
  minute. Local file lookups were a linear scan per song, so an
  8,890-song queue meant forty million comparisons on the main thread; a
  ready-song index answers each lookup directly, and the queue build runs
  off the main thread entirely. The library's background polling also
  settles down when nothing is transferring: it used to wake every two
  seconds forever while merely-queued records existed, because queued
  songs were treated as active progress. Only live DownloadManager
  transfers need the fast poll; record changes arrive on their own
  channel.

- Lyrics are cached per song, so a song whose lyrics were seen once shows
  them with no connection. The cache refreshes whenever the lyrics screen
  opens online, and saving a song in-app fetches its lyrics alongside its
  cover. No bulk lyrics pass: this server answers lyrics for almost
  nothing, and a full sweep would hammer it for hours to store mostly
  empty results.

- Album rows carry a download button everywhere they appear: the library
  grids and lists, search results, artist and genre pages, and the home
  screen. Tapping it queues the album's songs, and offline it uses the
  saved copy to decide what still needs downloading.

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
