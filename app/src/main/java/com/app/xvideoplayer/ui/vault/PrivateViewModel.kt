package com.app.xvideoplayer.ui.vault

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.app.xvideoplayer.data.model.VideoItem
import com.app.xvideoplayer.data.model.VideoSource
import com.app.xvideoplayer.data.repository.VideoRepository
import com.app.xvideoplayer.data.vault.PrivateVault
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A private video with its tags: [tags] = primary (its folder) first, then the secondary ones. */
data class PVideo(val video: VideoItem, val primary: String?, val tags: List<String>, val favorite: Boolean, val taggedAt: Long = 0L) {
    val path get() = video.path
}

data class PrivateIndex(
    val root: String?,
    val hidden: List<String>,
    /** Videos under the tagged root */
    val tagged: List<PVideo>,
    /** Videos in the hidden folders (outside the tagged root) */
    val loose: List<PVideo>,
    val tree: List<String>
) {
    val favorites get() = (tagged + loose).filter { it.favorite }
    /** Videos the tagger saved, most recent first */
    val recent get() = tagged.filter { it.taggedAt > 0 }.sortedByDescending { it.taggedAt }
    val all get() = tagged + loose

    /** Distinct videos carrying [tag] or any tag below it */
    fun countUnder(tag: String) = tagged.count { v -> v.tags.any { it == tag || it.startsWith("$tag/") } }

    /** Direct child tags of [tag] ("" = root) */
    fun childrenOf(tag: String): List<String> =
        tree.filter { t -> if (tag.isEmpty()) '/' !in t else t.startsWith("$tag/") && '/' !in t.removePrefix("$tag/") }

    /** Videos tagged exactly [tag] (stored there, or tagged secondarily) */
    fun videosAt(tag: String) = tagged.filter { tag in it.tags }

    /** Videos sitting right in the tagged root (no folder = no tag yet) */
    val untaggedInRoot get() = tagged.filter { it.primary == null }

    fun looseUnder(folder: String) = loose.filter { it.path.startsWith("$folder/") }
}

@HiltViewModel
class PrivateViewModel @Inject constructor(
    application: Application,
    private val repo: VideoRepository,
    val vault: PrivateVault,
    private val prefs: com.app.xvideoplayer.data.preferences.AppPreferences
) : AndroidViewModel(application) {

    // same layout/sort/shape settings as the Videos and Folders tabs
    val viewMode = prefs.viewMode.stateIn(viewModelScope, SharingStarted.Eagerly, "LIST")
    val folderView = prefs.folderView.stateIn(viewModelScope, SharingStarted.Eagerly, "LIST")
    val sortConfig = prefs.sortConfig.stateIn(viewModelScope, SharingStarted.Eagerly, com.app.xvideoplayer.data.model.SortConfig())
    val orientation = prefs.orientationFilter.stateIn(viewModelScope, SharingStarted.Eagerly, com.app.xvideoplayer.data.model.OrientationFilter.ALL)
    fun toggleViewMode() { viewModelScope.launch { prefs.setViewMode(when (viewMode.value) { "GRID" -> "LIST"; "LIST" -> "LIST1"; else -> "GRID" }) } }
    fun toggleFolderView() { viewModelScope.launch { prefs.setFolderView(if (folderView.value == "GRID") "LIST" else "GRID") } }
    fun setSortConfig(c: com.app.xvideoplayer.data.model.SortConfig) { viewModelScope.launch { prefs.setSortConfig(c) } }
    fun setOrientation(o: com.app.xvideoplayer.data.model.OrientationFilter) { viewModelScope.launch { prefs.setOrientationFilter(o) } }

    val unlocked = vault.unlocked
    val pinLength = vault.pin.map { it.length }.stateIn(viewModelScope, SharingStarted.Eagerly, PrivateVault.DEFAULT_PIN.length)

    val index: StateFlow<PrivateIndex?> = combine(
        repo.getVideosBySource(VideoSource.LOCAL), vault.privateRoots, vault.taggedRoot, vault.meta, vault.tagTree
    ) { all, roots, root, meta, tree ->
        val hidden = roots.filter { it != root }.sorted()
        val priv = all.filter { vault.isPrivate(it.path, roots) }
        fun wrap(v: VideoItem): PVideo {
            val m = meta[v.path]
            val primary = vault.primaryTagOf(v.path, root)
            val extras = m?.extraTags?.split('\n')?.filter { it.isNotBlank() && it != primary }.orEmpty()
            return PVideo(v, primary, listOfNotNull(primary) + extras, m?.favorite == true, m?.taggedAt ?: 0L)
        }
        val (tagged, loose) = priv.map(::wrap).partition { root != null && it.path.startsWith("$root/") }
        PrivateIndex(
            root, hidden,
            tagged.sortedByDescending { it.video.dateAdded },
            loose.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.video.title }),
            tree
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init { repo.ensureLibrarySynced() }

    suspend fun tryUnlock(pin: String) = vault.tryUnlock(pin)
    fun lock() = vault.lock()
    fun changePin(p: String) { viewModelScope.launch { vault.setPin(p) } }

    fun addHidden(path: String) { viewModelScope.launch { vault.addHiddenFolder(path); repo.requestFolderSync(path) } }
    fun removeHidden(path: String) { viewModelScope.launch { vault.removeHiddenFolder(path) } }
    fun setRoot(path: String) { viewModelScope.launch { vault.setTaggedRoot(path); repo.requestFolderSync(path) } }

    fun toggleFavorite(v: PVideo) { viewModelScope.launch { vault.setFavorite(v.path, !v.favorite) } }
    fun refresh() { vault.bumpTree(); repo.requestSync() }

    /** Prepares the player's previous/next to follow the list the video was opened from */
    fun setPlaylist(list: List<PVideo>) { vault.playlist = list.map { it.video.id } }
    fun queueForTagging(list: List<PVideo>) { vault.tagQueue = list.map { it.video.id } }

    /** Renames/moves a tag folder; links follow it. [onDone] gets the new tag, or null + shows why */
    fun moveTag(tag: String, newTag: String, merge: Boolean = false, onDone: (String?, String?, Boolean) -> Unit) {
        viewModelScope.launch {
            repo.moveTagFolder(tag, newTag, merge).onSuccess { onDone(it, null, false) }
                .onFailure { onDone(null, it.message, it is VideoRepository.TagFolderExists) }
        }
    }

    /** Deletes a tag folder; its videos move up one level. [onDone] gets how many files moved, or null + the reason */
    fun deleteTag(tag: String, onDone: (Int?, String?) -> Unit) {
        viewModelScope.launch { repo.deleteTagFolder(tag).onSuccess { onDone(it, null) }.onFailure { onDone(null, it.message) } }
    }
}
