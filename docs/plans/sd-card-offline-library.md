# Offline library on the SD card

Scope for a fork of Resonance that stores offline music on a removable card, in
original quality, for the whole library, with Navidrome as the source of truth
for play counts and favorites.

## Why the current code cannot do it

`SongDownloader.enqueue` hands every file to Android's `DownloadManager` with
`setDestinationInExternalPublicDir(DIRECTORY_MUSIC, ...)`. That writes to
`/storage/emulated/0/Music`, which is internal storage. `DownloadManager` has no
way to target a removable volume. `OfflineLibrary.inspect` then reads status and
URIs back out of `DownloadManager`, and `OfflineRecord.downloadId` is the only
link between a song and its file.

Everything else already works: `MediaItems` prefers `offline.localUri(song.id)`
over the stream URL, `PlaybackReporting` scrobbles the same song ids either way,
and the favorites worker reuses the same queue.

So the patch replaces the storage and transfer layer and leaves the rest alone.

## Pieces

### 1. Storage root (SAF)

New `OfflineStorage` singleton.

- Holds a persisted tree URI and the matching persisted permission
  (`ACTION_OPEN_DOCUMENT_TREE`, `takePersistableUriPermission`).
- Resolves a relative path to a document id, keeps a path to document id cache
  in memory, creates parents on demand, opens output streams, renames, deletes.
- Reports free bytes on the volume.
- Exposes a small interface so tests can substitute a fake.

Relative paths stay exactly what `downloadPath` produces today
(`Resonance/Artist/Album/NN - Title [key12].flac`), which keeps the naming
function as the single source of truth and makes pre-seeding possible.

Settings gets an "Offline storage" row: current folder, free space, a picker,
and a reset to internal storage. Unset means today's behaviour, so existing users
keep `DownloadManager`.

### 2. In-app transfer for SAF mode

`DownloadManager` cannot write through SAF, so downloads become an OkHttp stream.

- `SafDownloader` writes to `<name>.part`, then renames on success. A `.part`
  left by a crash is discarded on retry.
- `OfflineRecord` gains `relativePath: String?`. No custom root keeps
  `downloadId`; a custom root leaves `downloadId` at -1 and sets the path.
- `OfflineLibrary.inspect` branches: path mode reports QUEUED, DOWNLOADING with
  byte counts, READY with the document URI, or FAILED.
- `remove` deletes the document instead of calling `DownloadManager.remove`.

### 3. Long-running queue

A 300GB library at WiFi speed is many hours, so `DownloadWorker` becomes a
foreground worker with a progress notification, one file at a time, and these
properties:

- Idempotent: a file already present at its path is skipped.
- Per-file failure is recorded on that record and the queue moves on, so one bad
  file does not stop the run.
- Retry with backoff on transport errors.
- Progress notification shows songs remaining and current file.

### 4. Download the whole library

`LibrarySync` pages `getAlbumList2` and `getAlbum` to enumerate every song, then
queues them. `SubsonicApi` already has both calls.

- Downloads screen gets "Download entire library" with cancel, plus
  "songs ready / total" and bytes done.
- Hard stop when the volume has less than 5GB free.

### 5. Pre-seed over USB

Filling 300GB through the app is slow partly because it is HTTP over WiFi. The
file naming is a pure function of song metadata, song id, server URL and
username, so the paths can be computed off device.

Export a manifest (`relativePath<TAB>songId`) from the app into the storage root,
copy the library into those paths from a PC over USB, then "Import existing
files" walks the manifest and creates records for files it finds. After that the
app only downloads deltas.

Two decisions to make here: manifest import only, or also a full directory scan
(which handles files added later by other tools but costs a tree walk).

### 6. Scrobble retry queue

`PlaybackReporting.report` swallows failures, so plays in a dead zone are lost.
Queue `(songId, submission, timestamp)` in preferences and flush on network
return. Small and independent of the storage work.

## Tests

Unit tests, pure JVM, at these seams:

- `downloadPath` keeps producing stable names for a fixed song, including the
  disc/track numbering and the 12 character key suffix.
- Manifest line formatting round-trips, including titles with tabs and slashes.
- Manifest import creates records only for songs with a manifest line and a
  present file, and skips entries for another account.
- Library enumeration returns each song once across page boundaries.
- Scrobble queue: enqueue, flush order, dedupe, clear on success.

Instrumented test on the tablet:

- Write a file into a temp tree through `OfflineStorage`, see READY with a
  playable URI, delete it, see it gone.

Not covered by tests: WorkManager foreground behaviour and the notification.

## Risks

1. SAF throughput and traversal cost with 20k to 30k files. Mitigate with a
   document id cache and direct `DocumentsContract` calls instead of
   `DocumentFile` wrappers on the hot path. Prove it with 200 albums before
   committing the library.
2. Extension handling: SAF providers may append extensions to display names. The
   naming function must be verified against the real provider, and `inspect` must
   not depend on exact names if a provider renames.
3. Resume on partial files needs HTTP Range support from Navidrome. Unverified.
   Assume restart from zero per file.
4. Samsung battery management will kill long background work. The foreground
   worker helps; the user may also need to exempt the app from battery
   optimisation.
5. Artwork cache lives in internal `filesDir`. At roughly 100KB per album it
   stays small, but for a very large library it should move to the tree.
6. The fork cannot replace the F-Droid build (different signature). The debug
   build uses a `.debug` suffix, so it installs alongside for testing, and the
   fork stays separate from store updates.

## Order of work

Vertical slices, each one verifiable on the tablet:

1. Storage root plus one song: pick the SD folder, download one track into it,
   play it from the Downloads screen with the server unreachable.
2. Per-song and per-album downloads through SAF, including remove and retry.
3. Foreground queue across many files, including surviving a reboot.
4. Whole library enumeration and download all, with space check.
5. Manifest export and import, then a USB pre-seed of a few thousand files.
6. Scrobble retry queue.

Roughly 1,300 changed lines across a dozen files, most of them new. Stage 1 is
the one that decides whether the approach holds up, so it comes first.
