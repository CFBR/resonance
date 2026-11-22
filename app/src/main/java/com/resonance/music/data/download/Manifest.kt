package com.resonance.music.data.download

import com.google.gson.Gson
import com.resonance.music.data.api.models.SongItem

/**
 * One line of the offline manifest: where the app expects the song's file and
 * everything needed to build a playable record without asking the server.
 * JSON lines, so a title with tabs or quotes cannot corrupt the file, and so
 * importing works offline with no per-song API calls.
 */
data class ManifestEntry(val path: String, val song: SongItem)

private val manifestGson = Gson()

/** The manifest lives at the root of the picked storage folder. */
const val MANIFEST_NAME = "resonance-manifest.jsonl"

internal fun manifestLine(entry: ManifestEntry): String = manifestGson.toJson(entry)

internal fun parseManifestLine(line: String): ManifestEntry? {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return null
    return runCatching {
        val entry = manifestGson.fromJson(trimmed, ManifestEntry::class.java)
        if (entry.path.isBlank() || entry.song.id.isBlank()) null else entry
    }.getOrNull()
}

/**
 * Creates records for manifest entries whose file is present on the storage.
 * Returns how many were imported. Entries for missing files are skipped so a
 * partial copy can be imported and re-run. The record layer dedupes by key,
 * so importing twice is safe.
 */
internal suspend fun importManifestEntries(
    entries: Iterable<ManifestEntry>,
    fileExists: suspend (path: String) -> Boolean,
    recordFor: suspend (entry: ManifestEntry) -> Unit
): Int {
    var imported = 0
    for (entry in entries) {
        if (!fileExists(entry.path)) continue
        recordFor(entry)
        imported++
    }
    return imported
}
