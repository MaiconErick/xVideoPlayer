package com.app.xvideoplayer.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.app.xvideoplayer.data.model.*
import com.app.xvideoplayer.data.preferences.AppPreferences
import com.app.xvideoplayer.data.repository.LibrarySyncState
import com.app.xvideoplayer.data.repository.VideoRepository
import com.app.xvideoplayer.util.SearchMatch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FolderEntry(val folder: FolderItem, val cover: VideoItem?)

/**
 * Backs the Videos tab, the Folders tab and a single folder's page ([folderPath] set via nav args).
 * Lists are null until Room has answered once, so the UI never mistakes "loading" for "empty".
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    application: Application,
    savedStateHandle: SavedStateHandle,
    private val videoRepository: VideoRepository,
    private val appPreferences: AppPreferences
) : AndroidViewModel(application) {

    val folderPath: String? = savedStateHandle.get<String>("folder")?.takeIf { it.isNotBlank() }

    val syncState: StateFlow<LibrarySyncState> = videoRepository.syncState

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _filterConfig = MutableStateFlow(FilterConfig())
    val filterConfig: StateFlow<FilterConfig> = _filterConfig.asStateFlow()

    val sortConfig = appPreferences.sortConfig.stateIn(viewModelScope, SharingStarted.Eagerly, SortConfig())
    val orientation = appPreferences.orientationFilter.stateIn(viewModelScope, SharingStarted.Eagerly, OrientationFilter.ALL)
    val viewMode = appPreferences.viewMode.stateIn(viewModelScope, SharingStarted.Eagerly, "LIST")
    val folderMode = appPreferences.folderMode.stateIn(viewModelScope, SharingStarted.Eagerly, "ALL")
    val folderView = appPreferences.folderView.stateIn(viewModelScope, SharingStarted.Eagerly, "LIST")
    val continuePanelHidden = appPreferences.continuePanelHidden.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ── Multi-select (long-press a video) ──
    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()
    fun toggleSelect(id: Long) { _selection.update { if (id in it) it - id else it + id } }
    fun startSelect(id: Long) { _selection.update { it + id } }
    fun clearSelection() { _selection.value = emptySet() }
    fun selectAll(ids: List<Long>) { _selection.value = ids.toSet() }
    fun favoriteSelected(videos: List<VideoItem>) {
        val sel = videos.filter { it.id in _selection.value }
        val makeFav = sel.any { !it.isFavorite }
        viewModelScope.launch { sel.forEach { videoRepository.setFavorite(it.id, makeFav) } }
        clearSelection()
    }
    fun watchedSelected(videos: List<VideoItem>) {
        val sel = videos.filter { it.id in _selection.value }
        val markWatched = sel.any { it.playedStatus != PlayedStatus.WATCHED }
        viewModelScope.launch { sel.forEach { videoRepository.setPlayedStatus(it.id, if (markWatched) PlayedStatus.WATCHED else PlayedStatus.UNWATCHED) } }
        clearSelection()
    }
    val showContinueWatching = appPreferences.playerSettings.map { it.showContinueWatching }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val allLocal: StateFlow<List<VideoItem>?> = videoRepository.getPublicLocalVideos()
        .map<List<VideoItem>, List<VideoItem>?> { it }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Size of the whole local library (null = not loaded yet), to tell "empty" from "filtered away" */
    val libraryCount: StateFlow<Int?> = allLocal.map { it?.size }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val videos: StateFlow<List<VideoItem>?> = combine(allLocal, _searchQuery, _filterConfig, sortConfig, orientation) { all, query, filter, sort, orient ->
        all ?: return@combine null
        var list = if (folderPath != null) all.filter { it.folderPath == folderPath } else all
        if (query.isNotBlank()) list = list.filter { SearchMatch.matches(query, it.title, it.folderName) }
        list = when (orient) {
            OrientationFilter.ALL -> list
            OrientationFilter.PORTRAIT -> list.filter { it.isPortrait }
            OrientationFilter.LANDSCAPE -> list.filter { it.hasDimensions && !it.isPortrait }
        }
        filter.status?.let { s -> list = list.filter { it.playedStatus == s } }
        if (filter.favoritesOnly) list = list.filter { it.isFavorite }
        list = when (sort.field) {
            SortField.TITLE, SortField.FOLDER -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            SortField.DATE_ADDED -> list.sortedBy { it.dateAdded }
            SortField.LAST_PLAYED -> list.sortedBy { it.lastPlayed ?: 0L }
            SortField.DURATION -> list.sortedBy { it.duration }
            SortField.SIZE -> list.sortedBy { it.size }
            SortField.PLAY_COUNT -> list.sortedBy { it.playCount }
        }
        if (sort.order == SortOrder.DESCENDING) list.reversed() else list
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val continueWatching: StateFlow<List<VideoItem>> = allLocal.map { all ->
        all.orEmpty().filter { it.playedStatus == PlayedStatus.IN_PROGRESS && it.lastPosition > 0 }
            .sortedByDescending { it.lastPlayed ?: 0L }.take(12)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Flat list of every folder that directly holds videos, like MX Player. */
    val folders: StateFlow<List<FolderEntry>?> = combine(allLocal, _searchQuery, orientation) { all, query, orient ->
        all ?: return@combine null
        all.filter {
            when (orient) {
                OrientationFilter.ALL -> true
                OrientationFilter.PORTRAIT -> it.isPortrait
                OrientationFilter.LANDSCAPE -> it.hasDimensions && !it.isPortrait
            }
        }.groupBy { it.folderPath }
            .filter { (path, _) -> query.isBlank() || SearchMatch.matches(query, path.substringAfterLast('/')) }
            .map { (path, items) ->
                FolderEntry(
                    FolderItem(
                        path = path, name = path.substringAfterLast('/').ifBlank { "Storage" },
                        videoCount = items.size, totalSize = items.sumOf { it.size },
                        hasInProgress = items.any { it.playedStatus == PlayedStatus.IN_PROGRESS }
                    ),
                    cover = items.maxByOrNull { it.dateAdded }
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.folder.name })
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Nested view: the immediate subfolders of [folderPath] (or of the storage root on the Folders
     * tab), each counting every video below it.
     */
    val subfolders: StateFlow<List<FolderEntry>?> = combine(allLocal, orientation, _searchQuery) { all, orient, query ->
        all ?: return@combine null
        val vids = all.filter {
            when (orient) {
                OrientationFilter.ALL -> true
                OrientationFilter.PORTRAIT -> it.isPortrait
                OrientationFilter.LANDSCAPE -> it.hasDimensions && !it.isPortrait
            }
        }
        val entries = folderPath?.let { childrenOf(it, vids) } ?: rootEntries(vids)
        if (query.isBlank()) entries else entries.filter { SearchMatch.matches(query, it.folder.name) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun volumeRoot(path: String) =
        if (path.startsWith("/storage/emulated/0")) "/storage/emulated/0" else path.split('/').take(3).joinToString("/")

    private fun rootEntries(vids: List<VideoItem>): List<FolderEntry> {
        val roots = vids.map { volumeRoot(it.folderPath) }.distinct()
        if (roots.size == 1) {
            val root = roots[0]
            val direct = vids.filter { it.folderPath == root }
            val here = if (direct.isEmpty()) emptyList() else listOf(entryFor(root, "Storage root", direct, vids))
            return here + childrenOf(root, vids)
        }
        return roots.map { r ->
            entryFor(r, if (r == "/storage/emulated/0") "Internal storage" else "SD card (${r.substringAfterLast('/')})",
                vids.filter { it.folderPath == r || it.folderPath.startsWith("$r/") }, vids)
        }
    }

    private fun childrenOf(parent: String, vids: List<VideoItem>): List<FolderEntry> {
        val prefix = "$parent/"
        return vids.filter { it.folderPath.startsWith(prefix) }
            .groupBy { prefix + it.folderPath.removePrefix(prefix).substringBefore('/') }
            .map { (path, items) -> entryFor(path, path.substringAfterLast('/'), items, vids) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.folder.name })
    }

    private fun entryFor(path: String, name: String, items: List<VideoItem>, all: List<VideoItem>) = FolderEntry(
        FolderItem(
            path = path, name = name, videoCount = items.size, totalSize = items.sumOf { it.size },
            subfolderCount = items.map { it.folderPath }.filter { it != path && it.startsWith("$path/") }
                .map { it.removePrefix("$path/").substringBefore('/') }.distinct().size,
            hasInProgress = items.any { it.playedStatus == PlayedStatus.IN_PROGRESS }
        ),
        cover = items.maxByOrNull { it.dateAdded }
    )

    init {
        videoRepository.ensureLibrarySynced()
    }

    fun onPermissionGranted() = videoRepository.ensureLibrarySynced()
    /** Pull-to-refresh / refresh icon: inside a folder only that folder, elsewhere everything */
    fun refresh() = folderPath?.let { videoRepository.requestFolderSync(it) } ?: videoRepository.requestSync()
    /** Long-press on the refresh icon */
    fun refreshAll() = videoRepository.requestSync()
    fun setFolderMode(mode: String) { viewModelScope.launch { appPreferences.setFolderMode(mode) } }

    fun setSearchQuery(q: String) { _searchQuery.value = q }
    fun setFilterConfig(c: FilterConfig) { _filterConfig.value = c }
    fun setSortConfig(c: SortConfig) { viewModelScope.launch { appPreferences.setSortConfig(c) } }
    fun setOrientation(o: OrientationFilter) { viewModelScope.launch { appPreferences.setOrientationFilter(o) } }
    fun toggleViewMode() { viewModelScope.launch { appPreferences.setViewMode(when (viewMode.value) { "GRID" -> "LIST"; "LIST" -> "LIST1"; else -> "GRID" }) } }
    fun toggleFolderView() { viewModelScope.launch { appPreferences.setFolderView(if (folderView.value == "GRID") "LIST" else "GRID") } }
    fun setContinuePanelHidden(v: Boolean) { viewModelScope.launch { appPreferences.setContinuePanelHidden(v) } }

    /** What the floating play button resumes: the latest unfinished video, else whatever played last. */
    val resumeCandidate: StateFlow<VideoItem?> = allLocal.map { all ->
        val l = all.orEmpty()
        l.filter { it.playedStatus == PlayedStatus.IN_PROGRESS && it.lastPosition > 0 }.maxByOrNull { it.lastPlayed ?: 0L }
            ?: l.filter { it.lastPlayed != null }.maxByOrNull { it.lastPlayed ?: 0L }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun toggleFavorite(v: VideoItem) { viewModelScope.launch { videoRepository.setFavorite(v.id, !v.isFavorite) } }
    fun setPlayedStatus(id: Long, s: PlayedStatus) { viewModelScope.launch { videoRepository.setPlayedStatus(id, s) } }
    /** Removes the entry from the library only (the file stays; deleting files goes through FileActions) */
    fun removeFromLibrary(v: VideoItem) { viewModelScope.launch { videoRepository.deleteVideo(v.id) } }
}
