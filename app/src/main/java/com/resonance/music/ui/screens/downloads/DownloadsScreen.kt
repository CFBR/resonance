package com.resonance.music.ui.screens.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.resonance.music.data.download.*
import com.resonance.music.ui.components.LocalSongDownload

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(onBack: () -> Unit, viewModel: DownloadsViewModel = hiltViewModel()) {
    val tracks by viewModel.tracks.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val sync by viewModel.syncFavorites.collectAsState()
    val totals by viewModel.totals.collectAsState()
    val libraryRunning by viewModel.libraryRunning.collectAsState()
    val exportRunning by viewModel.exportRunning.collectAsState()
    val importRunning by viewModel.importRunning.collectAsState()
    val artworkRunning by viewModel.artworkRunning.collectAsState()
    val unmetered by viewModel.unmetered.collectAsState()
    val artwork by viewModel.artwork.collectAsState()
    val download = LocalSongDownload.current
    var removing by remember { mutableStateOf<OfflineTrack?>(null) }
    var removingGroup by remember { mutableStateOf<DownloadGroup?>(null) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(DownloadFilter.ALL) }
    removing?.let { track ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove download?") },
            text = { Text("Delete the saved copy of ${track.record.song.title} from this device?") },
            confirmButton = { TextButton(onClick = { viewModel.remove(track); removing = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } })
    }
    removingGroup?.let { group ->
        AlertDialog(onDismissRequest = { removingGroup = null }, title = { Text("Remove album?") },
            text = { Text("Delete the ${group.songs.size} saved copies of ${group.album} from this device?") },
            confirmButton = { TextButton(onClick = { viewModel.removeGroup(group); removingGroup = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removingGroup = null }) { Text("Cancel") } })
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Downloads") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                ListItem(headlineContent = { Text("Download favorite songs") },
                    supportingContent = { Text("Sync over Wi-Fi every 6 hours. Saved songs stay until you remove them.") },
                    trailingContent = { Switch(checked = sync, onCheckedChange = viewModel::setSyncFavorites) })
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val ready = tracks.any { it.status == OfflineStatus.READY }
                    Button(enabled = ready, onClick = { viewModel.play() }) { Icon(Icons.Default.PlayArrow, null); Text("Play all") }
                    OutlinedButton(enabled = ready, onClick = { viewModel.play(shuffle = true) }) { Icon(Icons.Default.Shuffle, null); Text("Shuffle") }
                }
                Text(
                    if (totals.known == 0) "No songs saved yet"
                    else "${totals.ready} of ${totals.known} songs saved, ${totals.queued} waiting",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium
                )
                ListItem(
                    headlineContent = { Text("Download entire library") },
                    supportingContent = { Text("Scan every album and queue missing songs. Transfers wait for Wi-Fi.") },
                    trailingContent = {
                        if (libraryRunning) OutlinedButton(onClick = viewModel::cancelLibraryDownload) { Text("Stop") }
                        else Button(onClick = viewModel::startLibraryDownload) { Text("Download all") }
                    }
                )
                ListItem(
                    headlineContent = { Text("Fill from a computer") },
                    supportingContent = { Text("Copy files into the folder you picked, or export the list first. Import marks present files ready without downloading.") },
                    trailingContent = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (exportRunning) OutlinedButton(onClick = viewModel::cancelManifestExport) { Text("Stop") }
                            else Button(onClick = viewModel::startManifestExport) { Text("Export list") }
                            if (importRunning) OutlinedButton(onClick = viewModel::cancelManifestImport) { Text("Stop") }
                            else OutlinedButton(onClick = viewModel::startManifestImport) { Text("Import") }
                        }
                    }
                )
                ListItem(
                    headlineContent = { Text("Fetch missing artwork") },
                    supportingContent = {
                        Column {
                            Text("Download a cover for every saved song that lacks one, so the offline library browses with complete artwork. Wi-Fi only.")
                            // Nothing to say before the library has loaded, and
                            // nothing to say for a library with no covers.
                            artwork?.takeIf { it.total > 0 }?.let {
                                Text(
                                    text = artworkLine(it),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    },
                    trailingContent = {
                        if (artworkRunning) OutlinedButton(onClick = viewModel::cancelArtworkPrefetch) { Text("Stop") }
                        else OutlinedButton(onClick = viewModel::startArtworkPrefetch) { Text("Fetch") }
                    }
                )
                if (tracks.isEmpty()) {
                    Text("Download songs, albums, playlists, or artists to listen here without a connection.", Modifier.padding(16.dp))
                } else {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it; viewModel.setQuery(it) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text("Search saved songs") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = ""; viewModel.setQuery("") }) { Icon(Icons.Default.Close, "Clear search") } },
                        singleLine = true
                    )
                    Row(
                        Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val ready = tracks.count { it.status == OfflineStatus.READY }
                        val waiting = tracks.count { it.status == OfflineStatus.QUEUED || it.status == OfflineStatus.DOWNLOADING }
                        val failed = tracks.count { it.status == OfflineStatus.FAILED }
                        FilterChip(selected = filter == DownloadFilter.ALL, onClick = { filter = DownloadFilter.ALL; viewModel.setStatusFilter(DownloadFilter.ALL) }, label = { Text("All ${tracks.size}") })
                        FilterChip(selected = filter == DownloadFilter.SAVED, onClick = { filter = DownloadFilter.SAVED; viewModel.setStatusFilter(DownloadFilter.SAVED) }, label = { Text("Saved $ready") })
                        FilterChip(selected = filter == DownloadFilter.WAITING, onClick = { filter = DownloadFilter.WAITING; viewModel.setStatusFilter(DownloadFilter.WAITING) }, label = { Text("Waiting $waiting") })
                        FilterChip(selected = filter == DownloadFilter.FAILED, onClick = { filter = DownloadFilter.FAILED; viewModel.setStatusFilter(DownloadFilter.FAILED) }, label = { Text("Failed $failed") })
                    }
                }
            }
            if (tracks.isNotEmpty() && groups.isEmpty()) item {
                Text("No songs match the search or filter.", Modifier.padding(16.dp))
            }
            groups.forEach { group ->
                item(key = "header:" + (group.songs.first().record.song.albumId ?: "song:" + group.songs.first().record.song.id)) {
                    ListItem(
                        headlineContent = { Text(group.album, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(listOfNotNull(group.artist, "${group.songs.size} songs").joinToString(" • "), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { AsyncImage(model = group.coverArt?.let(viewModel::coverArt), contentDescription = null, modifier = Modifier.size(48.dp)) },
                        trailingContent = { IconButton(onClick = { removingGroup = group }) { Icon(Icons.Default.DeleteOutline, "Remove album ${group.album}") } }
                    )
                }
                items(group.songs, key = { it.record.key }) { track ->
                    val song = track.record.song
                    Column {
                        ListItem(
                            headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(when (track.status) {
                                OfflineStatus.READY -> listOfNotNull(song.artist, song.album).joinToString(" • ")
                                OfflineStatus.QUEUED -> queuedReason(track, unmetered)
                                OfflineStatus.DOWNLOADING -> if (track.total > 0) "Downloading ${(track.bytes * 100 / track.total).coerceIn(0, 100)}%" else "Downloading"
                                OfflineStatus.FAILED -> track.record.error ?: "Download failed"
                            }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { AsyncImage(model = song.coverArt?.let(viewModel::coverArt), contentDescription = null, modifier = Modifier.size(48.dp)) },
                            trailingContent = {
                                Row {
                                    if (track.status == OfflineStatus.FAILED) IconButton(onClick = { download?.invoke(song) }) { Icon(Icons.Default.Refresh, "Retry ${song.title}") }
                                    IconButton(onClick = { removing = track }) { Icon(Icons.Default.DeleteOutline, "Remove ${song.title}") }
                                }
                            },
                            modifier = Modifier.clickable(enabled = track.status == OfflineStatus.READY) { viewModel.play(song.id) }
                        )
                        if (track.status == OfflineStatus.DOWNLOADING) {
                            if (track.total > 0) LinearProgressIndicator(progress = { (track.bytes.toFloat() / track.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}
