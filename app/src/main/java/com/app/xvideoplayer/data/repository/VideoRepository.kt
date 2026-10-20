package com.app.xvideoplayer.data.repository

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.app.xvideoplayer.data.db.dao.PlaybackHistoryDao
import com.app.xvideoplayer.data.db.dao.VideoDao
import com.app.xvideoplayer.data.db.entity.PlaybackHistoryEntity
import com.app.xvideoplayer.data.db.entity.VideoEntity
import com.app.xvideoplayer.data.model.*
import com.app.xvideoplayer.util.hasVideoPermission
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [isSyncing] is true while MediaStore is being diffed against the DB.
 * [hasSyncedOnce] lets the UI tell "library is empty" apart from "first scan still running".
 */
data class LibrarySyncState(val isSyncing: Boolean = false, val hasSyncedOnce: Boolean = false)

@Singleton
class VideoRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val videoDao: VideoDao,
    private val historyDao: PlaybackHistoryDao,
    private val vault: com.app.xvideoplayer.data.vault.PrivateVault
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private var pendingSync: Job? = null
    private var watching = false

    private val _syncState = MutableStateFlow(LibrarySyncState())
    val syncState: StateFlow<LibrarySyncState> = _syncState.asStateFlow()

    fun getAllVideos(): Flow<List<VideoItem>> = videoDao.getAllVideos().map { it.map { e -> e.toVideoItem() } }
    fun getVideosBySource(source: VideoSource): Flow<List<VideoItem>> = videoDao.getVideosBySource(source.name).map { it.map { e -> e.toVideoItem() } }
    /** Local videos minus everything in the private section (hidden folders + the tagged root) */
    fun getPublicLocalVideos(): Flow<List<VideoItem>> = combine(getVideosBySource(VideoSource.LOCAL), vault.privateRoots) { all, roots ->
        if (roots.isEmpty()) all else all.filterNot { vault.isPrivate(it.path, roots) }
    }
    fun getVideosByServer(serverId: Long): Flow<List<VideoItem>> = videoDao.getVideosByServer(serverId).map { it.map { e -> e.toVideoItem() } }
    fun getFavorites(): Flow<List<VideoItem>> = videoDao.getFavorites().map { it.map { e -> e.toVideoItem() } }
    fun searchVideos(query: String): Flow<List<VideoItem>> = videoDao.searchVideos(query).map { it.map { e -> e.toVideoItem() } }
    suspend fun getVideoById(id: Long): VideoItem? = videoDao.getVideoById(id)?.toVideoItem()
    suspend fun getVideoByUri(uri: String): VideoItem? = videoDao.getVideoByUri(uri)?.toVideoItem()
    suspend fun getVideoByPath(path: String): VideoItem? = videoDao.getVideoByPath(path)?.toVideoItem()
    suspend fun insertVideo(video: VideoItem): Long = videoDao.insertVideo(video.toEntity())
    suspend fun updateVideo(video: VideoItem) = videoDao.updateVideo(video.toEntity())

    /** Saves the resume point; [final] also writes a history row (once per viewing, not every tick). */
    suspend fun updatePlaybackPosition(id: Long, position: Long, final: Boolean = false) {
        videoDao.updatePlaybackPosition(id, position)
        val video = videoDao.getVideoById(id) ?: return
        val status = when {
            video.duration > 0 && position.toFloat() / video.duration > 0.92f -> PlayedStatus.WATCHED
            position > 0 -> PlayedStatus.IN_PROGRESS
            else -> PlayedStatus.UNWATCHED
        }
        videoDao.updatePlayedStatus(id, status.name)
        if (final) historyDao.insertEntry(PlaybackHistoryEntity(videoId = id, position = position, duration = video.duration, completed = status == PlayedStatus.WATCHED))
    }

    suspend fun setPlayedStatus(id: Long, status: PlayedStatus) {
        videoDao.updatePlayedStatus(id, status.name)
        if (status == PlayedStatus.UNWATCHED) videoDao.updatePlaybackPosition(id, 0L)
    }
    suspend fun incrementPlayCount(id: Long) = videoDao.incrementPlayCount(id)
    suspend fun setFavorite(id: Long, fav: Boolean) = videoDao.setFavorite(id, fav)
    suspend fun renameVideo(id: Long, newTitle: String) = videoDao.renameVideo(id, newTitle)
    suspend fun deleteVideo(id: Long) = videoDao.deleteVideoById(id)
    suspend fun deleteVideosByServer(serverId: Long) = videoDao.deleteVideosByServer(serverId)

    /** The player knows the real display size once a file is open; keep it for the filter and next launch. */
    suspend fun recordPlaybackInfo(id: Long, width: Int, height: Int, durationMs: Long) {
        if (width > 0 && height > 0) videoDao.updateDimensions(id, width, height)
        if (durationMs > 0) videoDao.fillDuration(id, durationMs)
    }

    // ── Library sync ────────────────────────────────────────────────────

    /**
     * Called whenever a library screen is shown. Only the first call per process syncs;
     * after that a MediaStore observer keeps the DB current, so returning from the player
     * never triggers another scan.
     */
    fun ensureLibrarySynced() {
        if (watching || !context.hasVideoPermission()) return
        watching = true
        context.contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = requestSync(debounceMs = 2_500)
            }
        )
        requestSync()
    }

    /** Pull-to-refresh / permission just granted. */
    fun requestSync(debounceMs: Long = 0) {
        pendingSync?.cancel()
        pendingSync = scope.launch {
            delay(debounceMs)
            syncLocalVideos()
        }
    }

    private suspend fun syncLocalVideos() = syncMutex.withLock {
        _syncState.update { it.copy(isSyncing = true) }
        try {
            diffWithMediaStore()
            probeMissingDimensions()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            _syncState.value = LibrarySyncState(isSyncing = false, hasSyncedOnce = true)
        }
    }

    /** One MediaStore query diffed against the DB in memory — no per-row DB lookups. */
    /**
     * Pull-to-refresh inside a folder: asks Android to re-scan just that folder's files (picks up
     * new/renamed/deleted ones immediately) and diffs only the library rows under it.
     */
    fun requestFolderSync(folder: String) {
        scope.launch {
            syncMutex.withLock {
                _syncState.update { it.copy(isSyncing = true) }
                try {
                    scanFolderFiles(folder)
                    diffWithMediaStore(folder)
                } catch (e: CancellationException) { throw e } catch (e: Exception) { e.printStackTrace() }
                finally { _syncState.value = LibrarySyncState(isSyncing = false, hasSyncedOnce = true) }
            }
        }
    }

    private suspend fun scanFolderFiles(folder: String) {
        val known = videoDao.getLocalVideosOnce().filter { it.path.substringBeforeLast('/') == folder }.map { it.path }
        val onDisk = java.io.File(folder).listFiles()?.filter { it.isFile && it.extension.lowercase() in com.app.xvideoplayer.util.VIDEO_EXTENSIONS }?.map { it.absolutePath }.orEmpty()
        val paths = (onDisk + known).distinct()
        if (paths.isEmpty()) return
        withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<Unit> { cont ->
                var left = paths.size
                android.media.MediaScannerConnection.scanFile(context, paths.toTypedArray(), null) { _, _ ->
                    synchronized(this) { left--; if (left == 0 && cont.isActive) cont.resumeWith(Result.success(Unit)) }
                }
            }
        }
    }

    /** [folder] limits the diff to that folder (and its subfolders); null = whole library. */
    private suspend fun diffWithMediaStore(folder: String? = null) {
        val hasOrientation = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val projection = buildList {
            add(MediaStore.Video.Media._ID); add(MediaStore.Video.Media.DISPLAY_NAME)
            add(MediaStore.Video.Media.DATA); add(MediaStore.Video.Media.DURATION)
            add(MediaStore.Video.Media.SIZE); add(MediaStore.Video.Media.DATE_ADDED)
            add(MediaStore.Video.Media.WIDTH); add(MediaStore.Video.Media.HEIGHT)
            if (hasOrientation) add(MediaStore.Video.Media.ORIENTATION)
        }.toTypedArray()

        // A null cursor means MediaStore is unavailable (e.g. permission revoked) — never wipe the library for that.
        val selection = folder?.let { "${MediaStore.Video.Media.DATA} LIKE ?" }
        val args = folder?.let { arrayOf("$it/%") }
        val cursor = context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, selection, args, null) ?: return

        // One row per file: if a path ever got two rows, keep the one carrying the watch history
        val byPath = videoDao.getLocalVideosOnce().let { all -> if (folder == null) all else all.filter { it.path.startsWith("$folder/") } }.groupBy { it.path }
        val extras = byPath.values.filter { it.size > 1 }.flatMap { rows ->
            rows.sortedWith(compareByDescending<VideoEntity> { it.lastPlayed ?: 0L }.thenByDescending { it.lastPosition }.thenBy { it.id }).drop(1)
        }
        extras.map { it.id }.chunked(500).forEach { videoDao.deleteVideosByIds(it) }
        val existing = byPath.mapValues { (_, rows) -> rows.first { it !in extras } }
        val seen = HashSet<String>(existing.size * 2)
        val inserts = mutableListOf<VideoEntity>()

        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val wCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val hCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val rotCol = if (hasOrientation) c.getColumnIndex(MediaStore.Video.Media.ORIENTATION) else -1

            while (c.moveToNext()) {
                val path = c.getString(pathCol) ?: continue
                seen += path
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol)).toString()
                val duration = c.getLong(durCol)
                val size = c.getLong(sizeCol)
                val rotation = if (rotCol >= 0) c.getInt(rotCol) else 0
                var w = c.getInt(wCol); var h = c.getInt(hCol)
                if (rotation == 90 || rotation == 270) { val t = w; w = h; h = t }

                val old = existing[path]
                if (old == null) {
                    val name = c.getString(nameCol) ?: path.substringAfterLast('/')
                    inserts += VideoEntity(
                        title = name.substringBeforeLast('.'), path = path, uri = uri,
                        duration = duration, size = size, source = VideoSource.LOCAL.name,
                        dateAdded = c.getLong(dateCol) * 1000, width = w, height = h
                    )
                } else {
                    val newW = if (w > 0) w else old.width
                    val newH = if (h > 0) h else old.height
                    val newDur = if (duration > 0) duration else old.duration
                    if (old.uri != uri || old.size != size || old.duration != newDur || old.width != newW || old.height != newH) {
                        videoDao.updateMediaInfo(old.id, uri, newDur, size, newW, newH)
                    }
                }
            }
        }

        inserts.chunked(200).forEach { videoDao.insertVideos(it) }
        val gone = existing.values.filter { it.path !in seen }.map { it.id }
        gone.chunked(500).forEach { videoDao.deleteVideosByIds(it) }
    }

    /** MediaStore leaves width/height at 0 for files it hasn't fully parsed; read those once from the file. */
    private suspend fun probeMissingDimensions() {
        val missing = videoDao.getLocalVideosOnce().filter { it.width <= 0 || it.height <= 0 }
        for (v in missing) {
            currentCoroutineContext().ensureActive()
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(v.path)
                var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: continue
                var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: continue
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
                videoDao.updateDimensions(v.id, w, h)
            } catch (_: Exception) {
            } finally {
                runCatching { r.release() }
            }
        }
    }

    /**
     * Moves a local video's file into [destDir] (renaming on a name clash) and keeps its library row,
     * so watch progress survives. Runs under the sync lock: a MediaStore diff never sees the file
     * half-moved. Needs all-files access for files other apps created. Returns the new path.
     */
    suspend fun moveFile(video: VideoItem, destDir: java.io.File): Result<String> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val src = java.io.File(video.path)
            if (!src.exists()) return@withLock Result.failure(IllegalStateException("File not found"))
            if (src.parentFile?.absolutePath == destDir.absolutePath) return@withLock Result.success(src.absolutePath)
            if (!destDir.isDirectory && !destDir.mkdirs()) return@withLock Result.failure(IllegalStateException("Couldn't create ${destDir.name}"))
            var dst = java.io.File(destDir, src.name)
            var n = 2
            while (dst.exists()) dst = java.io.File(destDir, "${src.nameWithoutExtension} ($n)${if (src.extension.isNotEmpty()) "." + src.extension else ""}").also { n++ }
            val moved = src.renameTo(dst) || runCatching {
                // different volume: copy, then drop the original
                src.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
                if (dst.length() == src.length()) src.delete() else { dst.delete(); false }
            }.getOrDefault(false)
            if (!moved) return@withLock Result.failure(IllegalStateException("Move failed — is all-files access allowed?"))
            videoDao.updatePathAndTitle(video.id, dst.absolutePath, dst.nameWithoutExtension)
            vault.movedFile(src.absolutePath, dst.absolutePath)
            scanPaths(listOf(src.absolutePath, dst.absolutePath))
            // pick up the new MediaStore uri for the same row
            runCatching { diffWithMediaStore(destDir.absolutePath) }
            Result.success(dst.absolutePath)
        }
    }

    /**
     * A cut the cutter just wrote ([path], MediaStore [uri]): makes sure it has a library row, then
     * moves it next to [original] so a private video's cut stays private.
     */
    suspend fun adoptCut(path: String, uri: String, original: VideoItem): Result<VideoItem> = withContext(Dispatchers.IO) {
        val row = syncMutex.withLock {
            videoDao.getVideoByPath(path) ?: run {
                val f = java.io.File(path)
                if (!f.exists()) return@withContext Result.failure(IllegalStateException("Cut file not found"))
                val duration = runCatching {
                    MediaMetadataRetriever().run { try { setDataSource(path); extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() } finally { release() } }
                }.getOrNull() ?: 0L
                val id = videoDao.insertVideo(
                    VideoEntity(
                        title = f.nameWithoutExtension, path = path, uri = uri, duration = duration, size = f.length(),
                        source = VideoSource.LOCAL.name, width = original.width, height = original.height
                    )
                )
                videoDao.getVideoById(id)
            }
        } ?: return@withContext Result.failure(IllegalStateException("Couldn't add the cut"))
        val item = row.toVideoItem()
        val dest = java.io.File(original.path).parentFile ?: return@withContext Result.success(item)
        moveFile(item, dest).map { newPath -> getVideoById(item.id) ?: item.copy(path = newPath) }
    }

    /**
     * Renames/moves the tag folder [tag] (relative to the tagged root) to [newTag], keeping every
     * link: library rows (watch progress), favorites and secondary tags all follow the new path.
     * A plain rename on the same storage, so it's instant however many videos are inside.
     */
    class TagFolderExists(val name: String) : IllegalStateException("“$name” already exists there")

    /**
     * Moves every file under [src] into [dst], merging folders that exist on both sides (same-named files
     * get a " (2)" suffix). Returns old path → new path for each file. Whole subfolders that don't clash move in one rename.
     */
    private fun mergeDirs(src: java.io.File, dst: java.io.File): List<Pair<String, String>> {
        val moved = mutableListOf<Pair<String, String>>()
        dst.mkdirs()
        for (child in src.listFiles().orEmpty()) {
            if (child.isDirectory) {
                val target = java.io.File(dst, child.name)
                if (!target.exists()) {
                    val files = child.walkTopDown().filter { it.isFile }.map { it.relativeTo(child).path }.toList()
                    if (child.renameTo(target)) { files.forEach { rel -> moved += java.io.File(child, rel).absolutePath to java.io.File(target, rel).absolutePath }; continue }
                }
                moved += mergeDirs(child, target)
                child.delete()
            } else {
                var t = java.io.File(dst, child.name); var n = 2
                while (t.exists()) t = java.io.File(dst, "${child.nameWithoutExtension} ($n)${if (child.extension.isNotEmpty()) "." + child.extension else ""}").also { n++ }
                if (child.renameTo(t)) moved += child.absolutePath to t.absolutePath
            }
        }
        return moved
    }

    /** Library rows, favorites and tags follow files moved by [mergeDirs]. */
    private suspend fun followMoves(moved: List<Pair<String, String>>, refreshDir: String) {
        for ((from, to) in moved) {
            videoDao.getVideoByPath(from)?.let { videoDao.updatePathAndTitle(it.id, to, java.io.File(to).nameWithoutExtension) }
            vault.movedFile(from, to)
        }
        vault.bumpTree()
        val scanned = moved.isEmpty() || scanPaths(moved.map { it.first } + moved.map { it.second })
        if (scanned) runCatching { diffWithMediaStore(refreshDir) }
    }

    /** Deletes the tag folder [tag]: its videos move up into the parent folder (nothing is deleted from disk). */
    suspend fun deleteTagFolder(tag: String): Result<Int> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val root = vault.taggedRoot.value ?: return@withLock Result.failure(IllegalStateException("No tagged folder"))
            if (tag.isEmpty()) return@withLock Result.failure(IllegalArgumentException("Can't delete the root"))
            val src = java.io.File(root, tag)
            if (!src.isDirectory) return@withLock Result.failure(IllegalStateException("Folder not found"))
            val parentTag = tag.substringBeforeLast('/', "")
            val parentDir = if (parentTag.isEmpty()) java.io.File(root) else java.io.File(root, parentTag)
            val moved = mergeDirs(src, parentDir)
            if (!src.delete() && src.walkTopDown().any { it.isFile }) return@withLock Result.failure(IllegalStateException("Some files couldn't be moved out — is all-files access allowed?"))
            src.deleteRecursively()
            followMoves(moved, parentDir.absolutePath)
            vault.deletedTagFolder(tag, parentTag)
            Result.success(moved.size)
        }
    }

    suspend fun moveTagFolder(tag: String, newTag: String, merge: Boolean = false): Result<String> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val root = vault.taggedRoot.value ?: return@withLock Result.failure(IllegalStateException("No tagged folder"))
            if (tag.isEmpty() || newTag.isEmpty()) return@withLock Result.failure(IllegalArgumentException("Name can't be empty"))
            if (newTag == tag) return@withLock Result.success(tag)
            if (newTag.startsWith("$tag/")) return@withLock Result.failure(IllegalArgumentException("Can't move a folder inside itself"))
            val src = java.io.File(root, tag)
            val dst = java.io.File(root, newTag)
            if (!src.isDirectory) return@withLock Result.failure(IllegalStateException("Folder not found"))
            // a case-only rename ("muscle" → "Muscle") is the same folder on Android's storage
            if (dst.exists() && !dst.absolutePath.equals(src.absolutePath, ignoreCase = true)) {
                if (!merge) return@withLock Result.failure(TagFolderExists(dst.name))
                val moved = mergeDirs(src, dst)
                src.deleteRecursively()
                followMoves(moved, dst.absolutePath)
                vault.movedTagFolder(tag, newTag)   // secondary tags that pointed at the old folder now point at the merged one
                return@withLock Result.success(newTag)
            }
            dst.parentFile?.mkdirs()
            val ok = if (dst.absolutePath.equals(src.absolutePath, ignoreCase = true)) {
                val tmp = java.io.File(src.parentFile, ".rename-${System.currentTimeMillis()}")
                src.renameTo(tmp) && tmp.renameTo(dst)
            } else src.renameTo(dst)
            if (!ok) return@withLock Result.failure(IllegalStateException("Rename failed — is all-files access allowed?"))
            val oldDir = src.absolutePath; val newDir = dst.absolutePath
            val moved = videoDao.getLocalVideosOnce().filter { it.path.startsWith("$oldDir/") }.map { it.path }
            videoDao.movePathPrefix(oldDir, newDir)
            vault.movedTagFolder(tag, newTag)
            vault.bumpTree()
            // let MediaStore forget the old paths and learn the new ones, then refresh uris for the same rows
            // (a diff before MediaStore caught up would drop the rows, so it waits for the scan)
            val scanned = moved.isEmpty() || scanPaths(moved + moved.map { newDir + it.removePrefix(oldDir) })
            if (scanned) runCatching { diffWithMediaStore(newDir) }
            Result.success(newTag)
        }
    }

    /** True when MediaStore finished scanning every path in time */
    private suspend fun scanPaths(paths: List<String>): Boolean =
        withTimeoutOrNull(10_000L + paths.size * 100L) {
            suspendCancellableCoroutine<Unit> { cont ->
                var left = paths.size
                android.media.MediaScannerConnection.scanFile(context, paths.toTypedArray(), null) { _, _ ->
                    synchronized(this) { left--; if (left == 0 && cont.isActive) cont.resumeWith(Result.success(Unit)) }
                }
            }
        } != null

    fun getRecentHistory(limit: Int = 50) = historyDao.getRecentHistory(limit)
    suspend fun clearHistory() = historyDao.clearAllHistory()

    fun VideoEntity.toVideoItem() = VideoItem(
        id = id, title = title, path = path, uri = uri, duration = duration, size = size,
        resolution = resolution, codec = codec, source = VideoSource.valueOf(source),
        serverId = serverId, thumbnailPath = thumbnailPath, lastPosition = lastPosition,
        playedStatus = PlayedStatus.valueOf(playedStatus), playCount = playCount,
        dateAdded = dateAdded, lastPlayed = lastPlayed, isFavorite = isFavorite,
        tags = if (tags.isBlank()) emptyList() else tags.split(","),
        width = width, height = height
    )

    fun VideoItem.toEntity() = VideoEntity(
        id = id, title = title, path = path, uri = uri, duration = duration, size = size,
        resolution = resolution, codec = codec, source = source.name, serverId = serverId,
        thumbnailPath = thumbnailPath, lastPosition = lastPosition, playedStatus = playedStatus.name,
        playCount = playCount, dateAdded = dateAdded, lastPlayed = lastPlayed, isFavorite = isFavorite,
        tags = tags.joinToString(","), width = width, height = height
    )
}
