package com.app.xvideoplayer.data.vault

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.app.xvideoplayer.data.db.dao.PrivateMetaDao
import com.app.xvideoplayer.data.db.entity.PrivateMetaEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val Context.vaultStore: DataStore<Preferences> by preferencesDataStore(name = "private_vault")

/** Tags are folder paths relative to the tagged root, "/"-separated; shown as "Muscle › Shirtless". */
fun tagLabel(tag: String) = tag.split('/').joinToString(" › ")
fun tagName(tag: String) = tag.substringAfterLast('/')

/**
 * The private section: a PIN, folders hidden from the regular library, and the "tagged" root whose
 * subfolders are the tags. Videos under any of those paths only show up inside the private section.
 */
@Singleton
class PrivateVault @Inject constructor(
    @ApplicationContext private val context: Context,
    private val metaDao: PrivateMetaDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store get() = context.vaultStore

    private object Keys {
        val PIN = stringPreferencesKey("pin")
        val HIDDEN = stringSetPreferencesKey("hidden_folders")
        val TAGGED_ROOT = stringPreferencesKey("tagged_root")
    }

    val pin: Flow<String> = store.data.map { it[Keys.PIN] ?: DEFAULT_PIN }
    val hiddenFolders: StateFlow<Set<String>> = store.data.map { it[Keys.HIDDEN].orEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())
    val taggedRoot: StateFlow<String?> = store.data.map { it[Keys.TAGGED_ROOT]?.takeIf { p -> p.isNotBlank() } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Every path whose videos are private. Cold and read straight from DataStore, so a combine()
     * with it waits for the real value instead of briefly showing private videos.
     */
    val privateRoots: Flow<Set<String>> = store.data.map { p ->
        p[Keys.HIDDEN].orEmpty() + listOfNotNull(p[Keys.TAGGED_ROOT]?.takeIf { it.isNotBlank() })
    }.distinctUntilChanged()
    private val rootsNow = privateRoots.stateIn(scope, SharingStarted.Eagerly, emptySet())

    fun isPrivate(path: String, roots: Set<String> = rootsNow.value) = roots.any { path == it || path.startsWith("$it/") }

    // ── lock ────────────────────────────────────────────────────────────
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    suspend fun tryUnlock(entered: String): Boolean = (entered == pin.first()).also { if (it) _unlocked.value = true }
    fun lock() { _unlocked.value = false }

    private var backgroundedAt = 0L
    fun onAppBackground() { backgroundedAt = android.os.SystemClock.elapsedRealtime() }
    /** A short grace period so a trip to Android's settings (or a delete confirmation) doesn't ask for the PIN again */
    fun onAppForeground() {
        if (backgroundedAt != 0L && android.os.SystemClock.elapsedRealtime() - backgroundedAt > LOCK_GRACE_MS) lock()
        backgroundedAt = 0L
    }

    suspend fun setPin(newPin: String) { store.edit { it[Keys.PIN] = newPin } }
    suspend fun addHiddenFolder(path: String) { store.edit { it[Keys.HIDDEN] = it[Keys.HIDDEN].orEmpty() + path.trimEnd('/') } }
    suspend fun removeHiddenFolder(path: String) { store.edit { it[Keys.HIDDEN] = it[Keys.HIDDEN].orEmpty() - path } }
    suspend fun setTaggedRoot(path: String) { store.edit { it[Keys.TAGGED_ROOT] = path.trimEnd('/') }; bumpTree() }

    // ── tags / favorites ────────────────────────────────────────────────
    val meta: Flow<Map<String, PrivateMetaEntity>> = metaDao.observeAll().map { l -> l.associateBy { it.path } }

    suspend fun setFavorite(path: String, favorite: Boolean) {
        val m = metaDao.get(path) ?: PrivateMetaEntity(path)
        metaDao.upsert(m.copy(favorite = favorite, updatedAt = System.currentTimeMillis()))
    }

    suspend fun isFavorite(path: String) = metaDao.get(path)?.favorite == true

    suspend fun setExtraTags(path: String, tags: List<String>) {
        val m = metaDao.get(path) ?: PrivateMetaEntity(path)
        val now = System.currentTimeMillis()
        metaDao.upsert(m.copy(extraTags = tags.distinct().joinToString("\n"), updatedAt = now, taggedAt = now))
    }

    /**
     * The tag folder [oldTag] was renamed/moved on disk to [newTag]: re-key the files under it and
     * point every secondary tag at the new path (subfolders included), so nothing loses its tags.
     */
    suspend fun movedTagFolder(oldTag: String, newTag: String) {
        val root = taggedRoot.value ?: return
        val oldDir = "$root/$oldTag"; val newDir = "$root/$newTag"
        fun retag(t: String) = when {
            t == oldTag -> newTag
            t.startsWith("$oldTag/") -> newTag + t.removePrefix(oldTag)
            else -> t
        }
        val changes = metaDao.getAll().mapNotNull { m ->
            val path = if (m.path.startsWith("$oldDir/")) newDir + m.path.removePrefix(oldDir) else m.path
            val extras = m.extraTags.split('\n').filter { it.isNotBlank() }.map(::retag).joinToString("\n")
            if (path == m.path && extras == m.extraTags) null else m.path to m.copy(path = path, extraTags = extras)
        }
        if (changes.isNotEmpty()) metaDao.rewrite(changes)
    }

    /** The tag folder [tag] was deleted (its files moved up into [parent]): secondary tags pointing at it follow, or vanish at the root. */
    suspend fun deletedTagFolder(tag: String, parent: String) {
        val changes = metaDao.getAll().mapNotNull { m ->
            val extras = m.extraTags.split('\n').filter { it.isNotBlank() }
                .mapNotNull { t -> if (t == tag || t.startsWith("$tag/")) parent.ifEmpty { null } else t }
                .distinct().joinToString("\n")
            if (extras == m.extraTags) null else m.path to m.copy(extraTags = extras)
        }
        if (changes.isNotEmpty()) metaDao.rewrite(changes)
    }

    suspend fun movedFile(from: String, to: String) = metaDao.movePath(from, to)
    suspend fun forget(path: String) = metaDao.delete(path)

    /** Primary tag = the file's folder relative to the tagged root; null when it isn't under it (or sits right in it). */
    fun primaryTagOf(path: String, root: String? = taggedRoot.value): String? {
        root ?: return null
        val folder = path.substringBeforeLast('/')
        if (!folder.startsWith("$root/")) return null
        return folder.removePrefix("$root/").ifBlank { null }
    }

    // ── the tag tree = folders under the tagged root ────────────────────
    private val treeVersion = MutableStateFlow(0)
    fun bumpTree() { treeVersion.update { it + 1 } }

    /** Every folder under the root as a tag, parents before children */
    val tagTree: StateFlow<List<String>> = combine(taggedRoot, treeVersion) { root, _ -> root }
        .map { root -> if (root == null) emptyList() else withContext(Dispatchers.IO) { scanTree(File(root)) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private fun scanTree(root: File): List<String> {
        val out = mutableListOf<String>()
        fun walk(dir: File, rel: String, depth: Int) {
            if (depth > 8) return
            dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
                ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                ?.forEach { d ->
                    val r = if (rel.isEmpty()) d.name else "$rel/${d.name}"
                    out += r
                    walk(d, r, depth + 1)
                }
        }
        walk(root, "", 0)
        return out
    }

    /** Creates [name] inside the tag folder [parent] ("" = root). Returns the new tag, or an error message. */
    suspend fun createTag(parent: String, name: String): Result<String> = withContext(Dispatchers.IO) {
        val root = taggedRoot.value ?: return@withContext Result.failure(IllegalStateException("Pick the tagged folder first"))
        val clean = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (clean.isEmpty()) return@withContext Result.failure(IllegalArgumentException("Name can't be empty"))
        val tag = if (parent.isEmpty()) clean else "$parent/$clean"
        val dir = File(root, tag)
        if (!dir.isDirectory && !dir.mkdirs()) return@withContext Result.failure(IllegalStateException("Couldn't create the folder (all-files access?)"))
        bumpTree()
        Result.success(tag)
    }

    // ── hand-offs between screens ───────────────────────────────────────
    /** Video ids the tagger should go through, set right before opening it */
    var tagQueue: List<Long> = emptyList()
    /** Cuts kept next to their original ("Keep both"), as (file path, MediaStore uri); the tagger queues them */
    val keptCuts = mutableListOf<Pair<String, String>>()
    /** Ordered ids for the player's previous/next when a video is opened from the private section */
    var playlist: List<Long> = emptyList()

    companion object {
        const val DEFAULT_PIN = "9635"
        private const val LOCK_GRACE_MS = 20_000L

        fun hasAllFilesAccess(): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    }
}
