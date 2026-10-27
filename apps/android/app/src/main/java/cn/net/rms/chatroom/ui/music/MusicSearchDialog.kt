package cn.net.rms.chatroom.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import cn.net.rms.chatroom.R
import cn.net.rms.chatroom.data.model.Playlist
import cn.net.rms.chatroom.data.model.PlaylistPreview
import cn.net.rms.chatroom.data.model.Song
import cn.net.rms.chatroom.ui.theme.*
import java.util.Locale

private const val TAB_SONGS = "songs"
private const val TAB_PLAYLISTS = "playlists"
private const val TAB_RECOMMEND = "recommend"

@Composable
fun MusicSearchDialog(
    searchResults: List<Song>,
    isSearching: Boolean,
    searchPlatform: String,
    onSearchPlatformChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onAddSong: (Song) -> Unit,
    onDismiss: () -> Unit,
    playlists: List<Playlist> = emptyList(),
    isSearchingPlaylists: Boolean = false,
    onSearchPlaylists: (String) -> Unit = {},
    playlistPreview: PlaylistPreview? = null,
    isLoadingPreview: Boolean = false,
    onOpenPlaylist: (String, String) -> Unit = { _, _ -> },
    onClosePreview: () -> Unit = {},
    onAddPlaylist: () -> Unit = {},
    recommendSongs: List<Song> = emptyList(),
    isFetchingRecommend: Boolean = false,
    onFetchRecommend: () -> Unit = {},
    onAddDaily: () -> Unit = {},
    radioPlatform: String = "",
    onStartRadio: (String) -> Unit = {},
    onStopRadio: () -> Unit = {}
) {
    var searchQuery by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(TAB_SONGS) }
    val focusRequester = remember { FocusRequester() }
    val platformOptions = listOf(
        "all" to stringResource(R.string.platform_all),
        "qq" to stringResource(R.string.music_platform_qq),
        "netease" to stringResource(R.string.music_platform_netease)
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    // The recommendation tab has no search box; its daily list loads on entry.
    LaunchedEffect(tab) {
        if (tab == TAB_RECOMMEND) onFetchRecommend()
    }

    val doSearch = {
        if (tab == TAB_PLAYLISTS) onSearchPlaylists(searchQuery) else onSearch(searchQuery)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.8f),
            shape = RoundedCornerShape(8.dp),
            color = Zhimo.paperSubtle
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Tab selector
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        TAB_SONGS to stringResource(R.string.music_tab_songs),
                        TAB_PLAYLISTS to stringResource(R.string.music_tab_playlists),
                        TAB_RECOMMEND to stringResource(R.string.music_tab_recommend)
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = tab == value,
                            onClick = { tab = value },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Zhimo.seal,
                                selectedLabelColor = Zhimo.paper,
                                containerColor = Zhimo.paperHover,
                                labelColor = Zhimo.inkFaint
                            )
                        )
                    }
                }

                // Search header (hidden on the recommendation tab)
                if (tab != TAB_RECOMMEND) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester),
                            placeholder = {
                                Text(
                                    stringResource(
                                        if (tab == TAB_PLAYLISTS) R.string.music_search_playlist_placeholder
                                        else R.string.music_search_placeholder
                                    ),
                                    color = Zhimo.inkFaint
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Search
                            ),
                            keyboardActions = KeyboardActions(
                                onSearch = { doSearch() }
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Zhimo.seal,
                                unfocusedBorderColor = Zhimo.paperHover,
                                focusedContainerColor = Zhimo.paperHover,
                                unfocusedContainerColor = Zhimo.paperHover,
                                cursorColor = Zhimo.seal,
                                focusedTextColor = Zhimo.ink,
                                unfocusedTextColor = Zhimo.ink
                            ),
                            shape = RoundedCornerShape(8.dp)
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        val searchBusy = if (tab == TAB_PLAYLISTS) isSearchingPlaylists else isSearching
                        IconButton(
                            onClick = { doSearch() },
                            enabled = !searchBusy,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Zhimo.seal)
                        ) {
                            if (searchBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Zhimo.paper,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = stringResource(R.string.action_search),
                                    tint = Zhimo.paper
                                )
                            }
                        }
                    }
                }

                // Platform selector
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    platformOptions.forEach { (value, label) ->
                        FilterChip(
                            selected = searchPlatform == value,
                            onClick = { onSearchPlatformChange(value) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Zhimo.seal,
                                selectedLabelColor = Zhimo.paper,
                                containerColor = Zhimo.paperHover,
                                labelColor = Zhimo.inkFaint
                            )
                        )
                    }
                }

                HorizontalDivider(color = Zhimo.paperHover)

                // Content
                when (tab) {
                    TAB_SONGS -> SongListSection(
                        songs = searchResults,
                        isBusy = isSearching,
                        hasQuery = searchQuery.isNotEmpty(),
                        onAddSong = {
                            onAddSong(it)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    )
                    TAB_PLAYLISTS -> {
                        if (playlistPreview != null) {
                            PlaylistPreviewSection(
                                preview = playlistPreview,
                                isLoading = isLoadingPreview,
                                onAddSong = {
                                    onAddSong(it)
                                    onDismiss()
                                },
                                onBack = onClosePreview,
                                onAddAll = {
                                    onAddPlaylist()
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            PlaylistListSection(
                                playlists = playlists,
                                isBusy = isSearchingPlaylists,
                                hasQuery = searchQuery.isNotEmpty(),
                                onOpen = onOpenPlaylist,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    TAB_RECOMMEND -> RecommendSection(
                        songs = recommendSongs,
                        isBusy = isFetchingRecommend,
                        radioPlatform = radioPlatform,
                        onAddSong = {
                            onAddSong(it)
                            onDismiss()
                        },
                        onAddDaily = {
                            onAddDaily()
                            onDismiss()
                        },
                        onStartRadio = onStartRadio,
                        onStopRadio = onStopRadio,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Close button
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Zhimo.paperHover
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(stringResource(R.string.action_close), color = Zhimo.ink)
                }
            }
        }
    }
}

@Composable
private fun SongListSection(
    songs: List<Song>,
    isBusy: Boolean,
    hasQuery: Boolean,
    onAddSong: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    if (songs.isEmpty()) {
        EmptyHint(
            text = when {
                isBusy -> stringResource(R.string.music_searching)
                hasQuery -> stringResource(R.string.music_no_results)
                else -> stringResource(R.string.music_search_hint)
            },
            modifier = modifier
        )
    } else {
        SongRows(songs, onAddSong, modifier)
    }
}

@Composable
private fun PlaylistListSection(
    playlists: List<Playlist>,
    isBusy: Boolean,
    hasQuery: Boolean,
    onOpen: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (playlists.isEmpty()) {
        EmptyHint(
            text = when {
                isBusy -> stringResource(R.string.music_searching)
                hasQuery -> stringResource(R.string.music_no_playlists)
                else -> stringResource(R.string.music_search_playlist_placeholder)
            },
            modifier = modifier
        )
    } else {
        LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(8.dp)
        ) {
            items(playlists) { playlist ->
                PlaylistItem(playlist) { onOpen(playlist.platform, playlist.id) }
            }
        }
    }
}

@Composable
private fun PlaylistPreviewSection(
    preview: PlaylistPreview,
    isLoading: Boolean,
    onAddSong: (Song) -> Unit,
    onBack: () -> Unit,
    onAddAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.music_back),
                    tint = Zhimo.ink,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.music_back), color = Zhimo.ink)
            }
            Text(
                text = stringResource(R.string.music_song_count, preview.total),
                style = MaterialTheme.typography.labelSmall,
                color = Zhimo.inkFaint,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = onAddAll,
                enabled = preview.songs.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Zhimo.seal),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Text(stringResource(R.string.music_add_all_to_queue), style = MaterialTheme.typography.labelMedium)
            }
        }
        if (preview.songs.isEmpty()) {
            EmptyHint(
                text = if (isLoading) stringResource(R.string.music_loading)
                else stringResource(R.string.music_no_results),
                modifier = Modifier.weight(1f)
            )
        } else {
            SongRows(preview.songs, onAddSong, Modifier.weight(1f))
        }
    }
}

@Composable
private fun RecommendSection(
    songs: List<Song>,
    isBusy: Boolean,
    radioPlatform: String,
    onAddSong: (Song) -> Unit,
    onAddDaily: () -> Unit,
    onStartRadio: (String) -> Unit,
    onStopRadio: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        // Radio ("guess you like") toggle: batches auto-extend server-side
        // when the queue is down to its last unplayed song.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.music_radio),
                style = MaterialTheme.typography.titleSmall,
                color = Zhimo.ink,
                modifier = Modifier.weight(1f)
            )
            if (radioPlatform.isNotEmpty()) {
                val platformName = stringResource(
                    if (radioPlatform == "qq") R.string.music_platform_qq
                    else R.string.music_platform_netease
                )
                Text(
                    text = stringResource(R.string.music_radio_running, platformName),
                    style = MaterialTheme.typography.labelSmall,
                    color = Zhimo.inkFaint
                )
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onStopRadio,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text(stringResource(R.string.music_stop_radio), style = MaterialTheme.typography.labelMedium)
                }
            } else {
                OutlinedButton(
                    onClick = { onStartRadio("qq") },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text(stringResource(R.string.music_platform_qq), style = MaterialTheme.typography.labelMedium)
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { onStartRadio("netease") },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text(stringResource(R.string.music_platform_netease), style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.music_daily_recommend),
                style = MaterialTheme.typography.titleSmall,
                color = Zhimo.ink,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = onAddDaily,
                enabled = songs.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Zhimo.seal),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Text(stringResource(R.string.music_add_all_to_queue), style = MaterialTheme.typography.labelMedium)
            }
        }

        if (songs.isEmpty()) {
            EmptyHint(
                text = if (isBusy) stringResource(R.string.music_loading)
                else stringResource(R.string.music_recommend_empty),
                modifier = Modifier.weight(1f)
            )
        } else {
            SongRows(songs, onAddSong, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SongRows(songs: List<Song>, onAddSong: (Song) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(8.dp)
    ) {
        items(songs) { song ->
            SearchResultItem(song = song, onClick = { onAddSong(song) })
        }
    }
}

@Composable
private fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Zhimo.inkFaint
        )
    }
}

@Composable
private fun PlaylistItem(
    playlist: Playlist,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(playlist.cover)
                .crossfade(true)
                .build(),
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Zhimo.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (playlist.platform == "qq") Color(0xFF10B981) else Color(0xFFE60026)
                ) {
                    Text(
                        text = if (playlist.platform == "qq") "QQ" else stringResource(R.string.music_platform_netease),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            val parts = buildList {
                add(stringResource(R.string.music_song_count, playlist.trackCount))
                if (playlist.creator.isNotBlank()) add(playlist.creator)
                if (playlist.playCount > 0) add(formatCompact(playlist.playCount))
            }
            Text(
                text = parts.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = Zhimo.inkFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// java.text's compact NumberFormat needs API 34; format by hand instead.
private fun formatCompact(value: Long): String {
    val zh = Locale.getDefault().language == "zh"
    fun scaled(divisor: Long, unit: String): String {
        val v = value.toDouble() / divisor
        val text = if (v >= 100) v.toLong().toString() else String.format(Locale.getDefault(), "%.1f", v)
        return text.trimEnd('.', '0').ifEmpty { "0" } + unit
    }
    return when {
        zh && value >= 100_000_000L -> scaled(100_000_000L, "亿")
        zh && value >= 10_000L -> scaled(10_000L, "万")
        !zh && value >= 1_000_000_000L -> scaled(1_000_000_000L, "B")
        !zh && value >= 1_000_000L -> scaled(1_000_000L, "M")
        !zh && value >= 1_000L -> scaled(1_000L, "K")
        else -> value.toString()
    }
}

@Composable
private fun SearchResultItem(
    song: Song,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Album cover
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(song.cover)
                .crossfade(true)
                .build(),
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Song info
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = song.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Zhimo.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(6.dp))
                // Platform tag
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (song.platform == "qq") Color(0xFF10B981) else Color(0xFFE60026)
                ) {
                    Text(
                        text = if (song.platform == "qq") "QQ" else stringResource(R.string.music_platform_netease),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                text = "${song.artist} · ${song.album}",
                style = MaterialTheme.typography.bodySmall,
                color = Zhimo.inkFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Duration
        Text(
            text = formatDuration(song.duration),
            style = MaterialTheme.typography.labelSmall,
            color = Zhimo.inkFaint
        )
    }
}

private fun formatDuration(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "$mins:${secs.toString().padStart(2, '0')}"
}
