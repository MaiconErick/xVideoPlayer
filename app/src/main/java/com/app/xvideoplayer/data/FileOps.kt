package com.app.xvideoplayer.data

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.app.xvideoplayer.data.db.dao.VideoDao
import com.app.xvideoplayer.data.model.VideoItem
import com.app.xvideoplayer.data.model.VideoSource
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

sealed interface FileOpResult {
    data object Done : FileOpResult
    /** Android wants the user to confirm (system dialog); call the matching finish* after RESULT_OK */
    data class NeedsConfirmation(val intentSender: IntentSender) : FileOpResult
    data class Failed(val message: String) : FileOpResult
}

/**
 * Real file operations on the device's videos (not just the library DB).
 * Files other apps created can only be changed after the system's own confirmation dialog
 * (Android 11+: createDeleteRequest / createWriteRequest), so callers may get NeedsConfirmation.
 */
@Singleton
class FileOps @Inject constructor(
    @ApplicationContext private val context: Context,
    private val videoDao: VideoDao
) {
    private val resolver get() = context.contentResolver

    suspend fun delete(videos: List<VideoItem>): FileOpResult = withContext(Dispatchers.IO) {
        val local = videos.filter { it.source == VideoSource.LOCAL && it.uri.startsWith("content://") }
        val others = videos - local.toSet()
        others.forEach { videoDao.deleteVideoById(it.id) }
        if (local.isEmpty()) return@withContext FileOpResult.Done
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // one system dialog for the whole batch (the dialog is skipped for files we own)
            val req = MediaStore.createDeleteRequest(resolver, local.map { Uri.parse(it.uri) })
            return@withContext FileOpResult.NeedsConfirmation(req.intentSender)
        }
        for (v in local) {
            try {
                resolver.delete(Uri.parse(v.uri), null, null)
                videoDao.deleteVideoById(v.id)
            } catch (e: RecoverableSecurityException) {
                return@withContext FileOpResult.NeedsConfirmation(e.userAction.actionIntent.intentSender)
            } catch (e: Exception) {
                runCatching { File(v.path).delete() }
                videoDao.deleteVideoById(v.id)
            }
        }
        FileOpResult.Done
    }

    /** After the user confirmed the system delete dialog. */
    suspend fun finishDelete(videos: List<VideoItem>) = withContext(Dispatchers.IO) {
        videos.forEach { v -> if (!File(v.path).exists() || v.source != VideoSource.LOCAL) videoDao.deleteVideoById(v.id) }
    }

    /** Renames the file itself (keeps the extension) and updates the library entry in place. */
    suspend fun rename(video: VideoItem, newTitle: String): FileOpResult = withContext(Dispatchers.IO) {
        val clean = newTitle.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (clean.isEmpty()) return@withContext FileOpResult.Failed("Name can't be empty")
        if (video.source != VideoSource.LOCAL || !video.uri.startsWith("content://")) {
            videoDao.renameVideo(video.id, clean)
            return@withContext FileOpResult.Done
        }
        val ext = video.path.substringAfterLast('.', "")
        val displayName = if (ext.isNotEmpty()) "$clean.$ext" else clean
        val uri = Uri.parse(video.uri)
        try {
            val values = ContentValues().apply { put(MediaStore.Video.Media.DISPLAY_NAME, displayName) }
            if (resolver.update(uri, values, null, null) <= 0) return@withContext FileOpResult.Failed("Rename failed")
            val newPath = resolver.query(uri, arrayOf(MediaStore.Video.Media.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: video.path.substringBeforeLast('/') + "/" + displayName
            videoDao.updatePathAndTitle(video.id, newPath, clean)
            FileOpResult.Done
        } catch (e: RecoverableSecurityException) {
            FileOpResult.NeedsConfirmation(e.userAction.actionIntent.intentSender)
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                FileOpResult.NeedsConfirmation(MediaStore.createWriteRequest(resolver, listOf(uri)).intentSender)
            else FileOpResult.Failed("No permission to rename this file")
        } catch (e: Exception) {
            FileOpResult.Failed(e.message ?: "Rename failed")
        }
    }

    /**
     * Replaces [target]'s file contents with [source] (e.g. a fresh cut), keeping the original's name,
     * folder and library entry, then deletes [source]. Android asks once to allow modifying the file.
     */
    suspend fun overwrite(target: VideoItem, source: Uri, newDurationMs: Long): FileOpResult =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val targetUri = Uri.parse(target.uri)
            try {
                val out = resolver.openOutputStream(targetUri, "wt") ?: return@withContext FileOpResult.Failed("Can't open the original for writing")
                out.use { o -> resolver.openInputStream(source)?.use { it.copyTo(o, 1 shl 20) } ?: return@withContext FileOpResult.Failed("Can't read the cut") }
                runCatching { resolver.delete(source, null, null) }
                val newSize = runCatching { resolver.openFileDescriptor(targetUri, "r")?.use { it.statSize } }.getOrNull() ?: target.size
                videoDao.updateMediaInfo(target.id, target.uri, newDurationMs, newSize, target.width, target.height)
                videoDao.updatePlaybackPosition(target.id, 0L)
                videoDao.updatePlayedStatus(target.id, "UNWATCHED")
                android.media.MediaScannerConnection.scanFile(context, arrayOf(target.path), null, null)
                FileOpResult.Done
            } catch (e: RecoverableSecurityException) {
                FileOpResult.NeedsConfirmation(e.userAction.actionIntent.intentSender)
            } catch (e: SecurityException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    FileOpResult.NeedsConfirmation(MediaStore.createWriteRequest(resolver, listOf(targetUri)).intentSender)
                else FileOpResult.Failed("No permission to modify the original")
            } catch (e: Exception) {
                FileOpResult.Failed(e.message ?: "Replace failed")
            }
        }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface FileOpsEntryPoint {
    fun fileOps(): FileOps
}
