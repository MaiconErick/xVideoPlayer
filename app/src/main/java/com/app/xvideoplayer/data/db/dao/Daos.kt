package com.app.xvideoplayer.data.db.dao

import androidx.room.*
import com.app.xvideoplayer.data.db.entity.VideoEntity
import com.app.xvideoplayer.data.db.entity.ServerEntity
import com.app.xvideoplayer.data.db.entity.PlaybackHistoryEntity
import com.app.xvideoplayer.data.db.entity.ThumbnailEntity
import com.app.xvideoplayer.data.db.entity.PrivateMetaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoDao {
    @Query("SELECT * FROM videos ORDER BY title ASC")
    fun getAllVideos(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE source = :source ORDER BY title ASC")
    fun getVideosBySource(source: String): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE serverId = :serverId ORDER BY title ASC")
    fun getVideosByServer(serverId: Long): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE id = :id")
    suspend fun getVideoById(id: Long): VideoEntity?

    @Query("SELECT * FROM videos WHERE uri = :uri LIMIT 1")
    suspend fun getVideoByUri(uri: String): VideoEntity?

    @Query("SELECT * FROM videos WHERE path = :path LIMIT 1")
    suspend fun getVideoByPath(path: String): VideoEntity?

    @Query("SELECT * FROM videos WHERE isFavorite = 1 ORDER BY title ASC")
    fun getFavorites(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE playedStatus = :status ORDER BY lastPlayed DESC")
    fun getVideosByStatus(status: String): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE title LIKE '%' || :query || '%' ORDER BY title ASC")
    fun searchVideos(query: String): Flow<List<VideoEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVideo(video: VideoEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVideos(videos: List<VideoEntity>)

    @Update
    suspend fun updateVideo(video: VideoEntity)

    @Query("UPDATE videos SET lastPosition = :position, lastPlayed = :timestamp WHERE id = :id")
    suspend fun updatePlaybackPosition(id: Long, position: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE videos SET playedStatus = :status WHERE id = :id")
    suspend fun updatePlayedStatus(id: Long, status: String)

    @Query("UPDATE videos SET playCount = playCount + 1 WHERE id = :id")
    suspend fun incrementPlayCount(id: Long)

    @Query("UPDATE videos SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: Long, isFavorite: Boolean)

    @Query("UPDATE videos SET title = :newTitle WHERE id = :id")
    suspend fun renameVideo(id: Long, newTitle: String)

    @Delete
    suspend fun deleteVideo(video: VideoEntity)

    @Query("DELETE FROM videos WHERE id = :id")
    suspend fun deleteVideoById(id: Long)

    @Query("DELETE FROM videos WHERE serverId = :serverId")
    suspend fun deleteVideosByServer(serverId: Long)

    @Query("SELECT * FROM videos WHERE source = 'LOCAL'")
    suspend fun getLocalVideosOnce(): List<VideoEntity>

    @Query("UPDATE videos SET uri = :uri, duration = :duration, size = :size, width = :width, height = :height WHERE id = :id")
    suspend fun updateMediaInfo(id: Long, uri: String, duration: Long, size: Long, width: Int, height: Int)

    @Query("UPDATE videos SET width = :width, height = :height WHERE id = :id")
    suspend fun updateDimensions(id: Long, width: Int, height: Int)

    @Query("UPDATE videos SET duration = :duration WHERE id = :id AND duration <= 0")
    suspend fun fillDuration(id: Long, duration: Long)

    @Query("UPDATE videos SET path = :path, title = :title WHERE id = :id")
    suspend fun updatePathAndTitle(id: Long, path: String, title: String)

    /** A folder was renamed/moved: every row under [oldDir] now lives under [newDir] */
    @Query("UPDATE videos SET path = :newDir || substr(path, length(:oldDir) + 1) WHERE source = 'LOCAL' AND substr(path, 1, length(:oldDir) + 1) = :oldDir || '/'")
    suspend fun movePathPrefix(oldDir: String, newDir: String)

    @Query("DELETE FROM videos WHERE id IN (:ids)")
    suspend fun deleteVideosByIds(ids: List<Long>)
}

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY name ASC")
    fun getAllServers(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE isActive = 1 ORDER BY name ASC")
    fun getActiveServers(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun getServerById(id: Long): ServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServer(server: ServerEntity): Long

    @Update
    suspend fun updateServer(server: ServerEntity)

    @Delete
    suspend fun deleteServer(server: ServerEntity)

    @Query("UPDATE servers SET lastConnected = :timestamp WHERE id = :id")
    suspend fun updateLastConnected(id: Long, timestamp: Long = System.currentTimeMillis())
}

@Dao
interface PlaybackHistoryDao {
    @Query("SELECT * FROM playback_history WHERE videoId = :videoId ORDER BY timestamp DESC")
    fun getHistoryForVideo(videoId: Long): Flow<List<PlaybackHistoryEntity>>

    @Query("SELECT * FROM playback_history ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentHistory(limit: Int = 50): Flow<List<PlaybackHistoryEntity>>

    @Insert
    suspend fun insertEntry(entry: PlaybackHistoryEntity)

    @Query("DELETE FROM playback_history WHERE videoId = :videoId")
    suspend fun clearHistoryForVideo(videoId: Long)

    @Query("DELETE FROM playback_history")
    suspend fun clearAllHistory()
}

@Dao
interface ThumbnailDao {
    @Query("SELECT * FROM thumbnails WHERE videoPath = :path")
    suspend fun getThumbnail(path: String): ThumbnailEntity?

    @Query("SELECT * FROM thumbnails WHERE serverId = :serverId")
    fun getThumbnailsByServer(serverId: Long): Flow<List<ThumbnailEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertThumbnail(thumbnail: ThumbnailEntity)

    @Query("DELETE FROM thumbnails WHERE serverId = :serverId")
    suspend fun deleteThumbnailsByServer(serverId: Long)
}

@Dao
interface PrivateMetaDao {
    @Query("SELECT * FROM private_meta")
    fun observeAll(): Flow<List<PrivateMetaEntity>>

    @Query("SELECT * FROM private_meta WHERE path = :path")
    suspend fun get(path: String): PrivateMetaEntity?

    @Query("SELECT * FROM private_meta")
    suspend fun getAll(): List<PrivateMetaEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meta: PrivateMetaEntity)

    @Query("DELETE FROM private_meta WHERE path = :path")
    suspend fun delete(path: String)

    /** Applies (old path → new row) pairs in one go */
    @Transaction
    suspend fun rewrite(changes: List<Pair<String, PrivateMetaEntity>>) {
        for ((old, m) in changes) { if (old != m.path) delete(old); upsert(m) }
    }

    /** The file moved: carry its tags/favorite over to the new path */
    @Transaction
    suspend fun movePath(from: String, to: String) {
        val m = get(from) ?: return
        delete(from)
        upsert(m.copy(path = to, updatedAt = System.currentTimeMillis()))
    }
}
