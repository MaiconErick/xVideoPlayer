package com.app.xvideoplayer.ui.vault

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.app.xvideoplayer.data.model.DecoderMode
import com.app.xvideoplayer.data.model.RepeatMode
import com.app.xvideoplayer.data.model.VideoItem
import com.app.xvideoplayer.data.model.VideoSource
import com.app.xvideoplayer.data.repository.VideoRepository
import com.app.xvideoplayer.data.vault.PrivateVault
import com.app.xvideoplayer.data.vault.tagLabel
import com.app.xvideoplayer.data.vault.tagName
import com.app.xvideoplayer.player.MpvPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Tags private videos one after another. Tags are folders under the tagged root: the first tag is
 * where the file is moved, the others are saved as secondary tags (the video shows up there too).
 *
 * Typing: the field searches folder names; Enter adds the best match as a tag (or, when nothing
 * matches, creates that folder inside the one open on the right and opens it). Enter on an empty
 * field tags the open folder, or saves when there is none.
 */
@HiltViewModel
class TaggerViewModel @Inject constructor(
    application: Application,
    private val repo: VideoRepository,
    private val vault: PrivateVault
) : AndroidViewModel(application) {

    val player = MpvPlayer(application, DecoderMode.AUTO)
    val tree: StateFlow<List<String>> = vault.tagTree

    /** How many videos carry each tag, to rank suggestions */
    val usage: StateFlow<Map<String, Int>> = combine(repo.getVideosBySource(VideoSource.LOCAL), vault.meta, vault.taggedRoot) { all, meta, root ->
        val counts = HashMap<String, Int>()
        if (root != null) for (v in all) {
            if (!v.path.startsWith("$root/")) continue
            val tags = listOfNotNull(vault.primaryTagOf(v.path, root)) + meta[v.path]?.extraTags?.split('\n').orEmpty().filter { it.isNotBlank() }
            tags.distinct().forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        counts
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Files the tagger saved before, most recent first — the "Edits" list to reopen and fix one */
    val recentEdits: StateFlow<List<VideoItem>> = combine(repo.getVideosBySource(VideoSource.LOCAL), vault.meta) { all, meta ->
        all.mapNotNull { v -> meta[v.path]?.taggedAt?.takeIf { it > 0 }?.let { v to it } }
            .sortedByDescending { it.second }.take(200).map { it.first }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var queue by mutableStateOf<List<VideoItem>>(emptyList()); private set
    var pos by mutableIntStateOf(0); private set
    val current: VideoItem? get() = queue.getOrNull(pos)
    var loaded by mutableStateOf(false); private set
    var finished by mutableStateOf(false); private set

    /** Chosen tags: [0] = primary (the file moves there). People tags always sit after the folder ones. */
    var chips by mutableStateOf<List<String>>(emptyList()); private set
    private fun putChips(list: List<String>) { chips = list.distinct().let { l -> l.filterNot { isPeople(it) } + l.filter { isPeople(it) } } }
    var text by mutableStateOf(TextFieldValue("")); private set
    /** Folder open in the right panel ("" = the tagged root); new tags are created here */
    var browse by mutableStateOf(""); private set
    var muted by mutableStateOf(false); private set
    var fullscreen by mutableStateOf(false)
    /** Player controls drawn over the video; tapping the video toggles them */
    var controlsVisible by mutableStateOf(false)
    /** "+ New": the input box names a new folder inside [browse] instead of searching */
    var newFolderMode by mutableStateOf(false); private set
    private val prefs = application.getSharedPreferences("tagger", android.content.Context.MODE_PRIVATE)
    /**
     * Match on: Enter adds the best-matching folder anywhere in the tree.
     * Match off: Enter opens the typed folder inside the open one (creating it if missing); Enter on empty tags it.
     */
    var matchMode by mutableStateOf(prefs.getBoolean("match", false)); private set
    fun toggleMatch() { matchMode = !matchMode; prefs.edit().putBoolean("match", matchMode).apply() }
    /** "Input name": the folder panel lists People/Stars and the box types a name */
    var peopleMode by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var deleteArmed by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    /** Short fading pop-up ("Added to People › name"), shown ~1 s; the Long makes repeats of the same text still trigger */
    var flash by mutableStateOf<Pair<String, Long>?>(null); private set
    fun showFlash(text: String) { flash = text to System.nanoTime() }
    /** While a past video is reopened from Edits: the video that was being sorted, so there is a way back to it */
    var returnTo by mutableStateOf<VideoItem?>(null); private set

    val root: String? get() = vault.taggedRoot.value

    init {
        player.setRepeatMode(RepeatMode.ONE)
        val ids = vault.tagQueue
        viewModelScope.launch {
            queue = ids.mapNotNull { repo.getVideoById(it) }
            // the folder scan feeds the People › Stars auto-tag of the first video
            kotlinx.coroutines.withTimeoutOrNull(2_000) { vault.tagTree.first { it.isNotEmpty() } }
            loaded = true
            if (queue.isEmpty()) finished = true else openCurrent()
        }
    }

    private suspend fun openCurrent() {
        val v = current ?: run { finished = true; return }
        deleteArmed = false
        text = TextFieldValue("")
        peopleMode = false
        newFolderMode = false
        controlsVisible = false
        val primary = vault.primaryTagOf(v.path)
        val extras = vault.meta.first()[v.path]?.extraTags?.split('\n')?.filter { it.isNotBlank() && it != primary }.orEmpty()
        putChips(listOfNotNull(primary) + extras)
        // the uploader already has a People › Stars folder: list the video there too
        if (chips.none { isPeople(it) }) username?.let { name ->
            peopleMatches(name).firstOrNull { tagName(it).equals(name, true) }?.let { putChips(chips + it); showFlash("Auto-added to ${tagLabel(it)}") }
        }
        browse = ""   // every video starts browsing from the root
        if (v.id == returnTo?.id) returnTo = null
        player.load(v.path, 0L)
        player.setVolume(if (muted) 0f else 1f)
        if (v.id in originalIds) message = "Now the ORIGINAL (its cut is done)"
    }

    /** "CUT" / "ORIGINAL" for videos involved in a cut made here, else null */
    fun roleOf(v: VideoItem?): String? = when (v?.id) {
        null -> null
        in cutIds -> "CUT"
        in originalIds -> "ORIGINAL"
        else -> null
    }

    fun toggleMute() { muted = !muted; player.setVolume(if (muted) 0f else 1f) }

    // ── typing ──────────────────────────────────────────────────────────
    fun onTextChange(v: TextFieldValue) { text = v }

    /** Folders matching the typed text, best first */
    fun matches(query: String, all: List<String> = tree.value): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return all.sortedWith(compareByDescending<String> { usage.value[it] ?: 0 }.thenBy { tagLabel(it).lowercase() })
        val use = usage.value
        fun rank(t: String): Int {
            val name = tagName(t)
            return when {
                name.equals(q, true) -> 0
                name.startsWith(q, true) -> 1
                name.split(' ', '-', '_').any { it.startsWith(q, true) } -> 2
                name.contains(q, true) -> 3
                t.contains(q, true) -> 4
                else -> 9
            }
        }
        return all.map { it to rank(it) }.filter { it.second < 9 }
            .sortedWith(compareBy<Pair<String, Int>> { it.second }.thenByDescending { use[it.first] ?: 0 }.thenBy { it.first.count { c -> c == '/' } }.thenBy { it.first.lowercase() })
            .map { it.first }
    }

    fun best(query: String): String? = if (query.isBlank()) null else matches(query).firstOrNull()

    /** Enter: add the best match, create a new tag, or (empty field) save */
    fun onEnter() {
        val q = text.text.trim()
        if (peopleMode) {
            if (q.isEmpty()) { closePeople(); return }
            pickPerson(peopleMatches(q).firstOrNull { tagName(it).equals(q, true) } ?: peopleMatches(q).firstOrNull()?.takeIf { tagName(it).startsWith(q, true) } ?: q)
            return
        }
        // empty field inside a folder: Enter tags that folder (Enter again saves)
        if (q.isEmpty() && !newFolderMode && browse.isNotEmpty() && browse !in chips) { addTag(browse); return }
        if (newFolderMode) {
            if (q.isNotEmpty()) createFolder(q) { err -> if (err != null) message = err }
            newFolderMode = false
            return
        }
        if (q.isEmpty()) { done(); return }
        // Match off: the typed name is a folder inside the open one — open it, creating it if missing
        if (!matchMode) { createFolder(q) { err -> if (err != null) message = err }; return }
        val match = best(q)
        if (match != null) { addTag(match); return }
        createFolder(q) { err -> if (err != null) message = err }
    }

    /** Backspace on an empty field removes the last tag */
    fun onBackspaceEmpty() { if (chips.isNotEmpty()) putChips(chips.dropLast(1)) }

    /** Adding a tag also takes the folder panel back to the root, ready for the next one */
    fun addTag(tag: String) {
        // a People tag only lists the video: setChips keeps real folders first (that's where it moves)
        if (tag !in chips) putChips(chips + tag)
        text = TextFieldValue("")
        browse = ""
    }

    // ── People ──────────────────────────────────────────────────────────
    /** The uploader: the file name up to the first hyphen ("0s0t3v3rg0n - … - Chudai [id]" → "0s0t3v3rg0n") */
    val username: String?
        get() {
            val t = current?.title ?: return null
            if ('-' !in t) return null
            return t.substringBefore('-').trim(' ', '_', '.').takeIf { it.isNotEmpty() }
        }

    fun peopleTagFor(name: String) = "$PEOPLE/" + name.replace(Regex("[\\\\/:*?\"<>|]"), "_")

    /** Creates xVideos/People/Stars/<username> if needed and lists the video there too */
    fun addToPeople() {
        val name = username ?: return
        viewModelScope.launch {
            // keeps the folder panel and typed text exactly as they are (adding a regular tag resets them, this must not)
            vault.createTag(PEOPLE, name).onSuccess { putChips(chips + it); showFlash("Added to ${tagLabel(it)}") }.onFailure { message = it.message }
        }
    }

    fun openPeople() { peopleMode = true; newFolderMode = false; text = TextFieldValue("") }
    fun closePeople() { peopleMode = false; text = TextFieldValue("") }

    /** Existing People/Stars/<name> folders matching [q] */
    fun peopleMatches(q: String): List<String> {
        val people = tree.value.filter { isPeople(it) && '/' !in it.removePrefix("$PEOPLE/") }
        if (q.isBlank()) return people.sortedBy { tagName(it).lowercase() }
        return people.filter { tagName(it).contains(q, true) }
            .sortedWith(compareBy<String> { if (tagName(it).startsWith(q, true)) 0 else 1 }.thenBy { tagName(it).lowercase() })
    }

    /** Replaces the video's People tag with [nameOrTag] (an existing People tag, or a new name to create) */
    fun pickPerson(nameOrTag: String) {
        viewModelScope.launch {
            val tag = if (isPeople(nameOrTag)) Result.success(nameOrTag) else vault.createTag(PEOPLE, nameOrTag)
            tag.onSuccess { t ->
                putChips(chips.filterNot { isPeople(it) })
                addTag(t)
                peopleMode = false
                message = "Also in ${tagLabel(t)}"
            }.onFailure { message = it.message }
        }
    }

    fun toggleTag(tag: String) { if (tag in chips) removeTag(tag) else addTag(tag) }
    fun removeTag(tag: String) { putChips(chips - tag) }
    /** People is never where the file goes, so it can't be made primary */
    fun makePrimary(tag: String) { if (isPeople(tag)) message = "People can't be the main folder" else putChips(listOf(tag) + (chips - tag)) }

    // ── folder panel ────────────────────────────────────────────────────
    fun startNewFolder() { newFolderMode = true; peopleMode = false; text = TextFieldValue("") }
    fun cancelNewFolder() { newFolderMode = false; text = TextFieldValue("") }

    fun openDir(tag: String) { browse = tag }
    fun goUp() { browse = browse.substringBeforeLast('/', "") }
    /** The folder [name] already existing inside the open one (any letter case), if any */
    fun existingChild(name: String): String? {
        val n = name.trim()
        return tree.value.firstOrNull { t -> t.substringBeforeLast('/', "") == browse && tagName(t).equals(n, true) }
    }

    /** "shirtless guys" → "Shirtless Guys" (the rest of each word is kept as typed) */
    fun capitalized(name: String) = name.trim().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }

    fun createFolder(name: String, onDone: (String?) -> Unit) {
        existingChild(name)?.let { browse = it; text = TextFieldValue(""); onDone(null); return }
        viewModelScope.launch {
            // open the new folder (room for subfolders) instead of tagging with it
            vault.createTag(browse, capitalized(name)).onSuccess { browse = it; text = TextFieldValue(""); onDone(null) }.onFailure { onDone(it.message) }
        }
    }

    // ── actions ─────────────────────────────────────────────────────────
    fun done() {
        val v = current ?: return
        val r = root ?: run { message = "Pick the tagged folder first"; return }
        if (chips.isEmpty()) { message = "Type a tag and press Enter first"; return }
        if (isPeople(chips.first())) { message = "Add a folder too — People only lists it"; return }
        if (busy) return
        busy = true
        viewModelScope.launch {
            val primary = chips.first()
            repo.moveFile(v, File(r, primary))
                .onSuccess { newPath ->
                    vault.setExtraTags(newPath, chips.drop(1))
                    message = "Saved in ${tagLabel(primary)}" + if (chips.size > 1) " +${chips.size - 1}" else ""
                    next()
                }
                .onFailure { message = it.message }
            busy = false
        }
    }

    fun skip() { viewModelScope.launch { next() } }

    /** Reopens a video saved earlier (from the Edits list) right here; the queue continues after it */
    fun editPast(video: VideoItem) {
        viewModelScope.launch {
            val fresh = repo.getVideoById(video.id) ?: run { message = "That file is gone"; return@launch }
            if (returnTo == null) returnTo = current?.takeIf { it.id != fresh.id }
            val before = queue.take(pos).filterNot { it.id == fresh.id }
            val after = queue.drop(pos).filterNot { it.id == fresh.id }
            queue = before + fresh + after
            pos = before.size
            openCurrent()
        }
    }

    /** Leaves the reopened past video and goes back to the one that was being sorted */
    fun backToSorting() {
        val r = returnTo ?: return
        viewModelScope.launch {
            val idx = queue.indexOfFirst { it.id == r.id }
            returnTo = null
            if (idx >= 0) { pos = idx; openCurrent() } else message = "That video is no longer in the queue"
        }
    }

    // ── cut ──────────────────────────────────────────────────────────────
    /** Set when the cut editor is opened from here; the file may come back replaced */
    var cutOpened = false
    fun onCutOpened() { cutOpened = true; vault.keptCuts.clear(); player.pause() }

    /** Cuts made from here, and the videos they came from — labelled so it's clear which one is open */
    var cutIds by mutableStateOf<Set<Long>>(emptySet()); private set
    var originalIds by mutableStateOf<Set<Long>>(emptySet()); private set

    /**
     * Back from the cut editor: re-read the file (it may have been replaced) but keep the chosen tags.
     * Cuts kept with "Keep both" are moved next to the original and queued first, then the original.
     */
    fun reloadAfterCut() {
        if (!cutOpened) return
        cutOpened = false
        viewModelScope.launch {
            val cur = current ?: return@launch
            val fresh = repo.getVideoById(cur.id) ?: return@launch
            val kept = vault.keptCuts.toList().also { vault.keptCuts.clear() }
            val cuts = kept.mapNotNull { (path, uri) -> repo.adoptCut(path, uri, fresh).onFailure { message = it.message }.getOrNull() }
            if (cuts.isEmpty()) {
                queue = queue.toMutableList().also { it[pos] = fresh }
                player.load(fresh.path, 0L)
                player.setVolume(if (muted) 0f else 1f)
                return@launch
            }
            val picked = chips
            queue = queue.take(pos) + cuts + fresh + queue.drop(pos + 1)
            cutIds = cutIds + cuts.map { it.id }
            originalIds = originalIds + fresh.id
            openCurrent()
            // the tags picked for the original are a good start for its cut
            if (chips.isEmpty()) putChips(picked)
            message = if (cuts.size == 1) "Now tagging the CUT — the original is next" else "Now tagging cut 1 of ${cuts.size} — the original comes after"
        }
    }

    /** The current file was deleted from disk */
    fun onDeleted() {
        val v = current ?: return
        viewModelScope.launch {
            vault.forget(v.path)
            queue = queue.filterNot { it.id == v.id }
            if (pos >= queue.size) finished = true else openCurrent()
        }
    }

    private suspend fun next() {
        if (pos + 1 >= queue.size) { player.pause(); finished = true; return }
        pos++
        openCurrent()
    }

    companion object {
        const val PEOPLE = "People/Stars"
        fun isPeople(tag: String) = tag.startsWith("$PEOPLE/")
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}
