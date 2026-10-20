package com.app.xvideoplayer.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "videos")
data class VideoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val path: String,
    val uri: String,
    val duration: Long = 0L,
    val size: Long = 0L,
    val resolution: String = "",
    val codec: String = "",
    val source: String = "LOCAL",
    val serverId: Long? = null,
    val thumbnailPath: String? = null,
    val lastPosition: Long = 0L,
    val playedStatus: String = "UNWATCHED",
    val playCount: Int = 0,
    val dateAdded: Long = System.currentTimeMillis(),
    val lastPlayed: Long? = null,
    val isFavorite: Boolean = false,
    val tags: String = "", // comma-separated
    @ColumnInfo(defaultValue = "0") val width: Int = 0,
    @ColumnInfo(defaultValue = "0") val height: Int = 0
)

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String, // SMB, FTP
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = "",
    val remotePath: String = "/",
    val streamMode: String = "STREAM",
    val isActive: Boolean = true,
    val lastConnected: Long? = null
)

@Entity(
    tableName = "playback_history",
    foreignKeys = [ForeignKey(
        entity = VideoEntity::class,
        parentColumns = ["id"],
        childColumns = ["videoId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("videoId")]
)
data class PlaybackHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: Long,
    val position: Long,
    val duration: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val completed: Boolean = false
)

/**
 * Private-section data, keyed by file path (library rows get new ids when files move).
 * The primary tag is the folder the file sits in under the tagged root; [extraTags] holds the
 * secondary ones as root-relative folder paths ("Muscle/Shirtless"), newline-separated.
 */
@Entity(tableName = "private_meta")
data class PrivateMetaEntity(
    @PrimaryKey val path: String,
    @ColumnInfo(defaultValue = "''") val extraTags: String = "",
    @ColumnInfo(defaultValue = "0") val favorite: Boolean = false,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    /** Last time the tagger saved this file (0 = never) — drives the "Recent edits" list */
    @ColumnInfo(defaultValue = "0") val taggedAt: Long = 0L
)

@Entity(tableName = "thumbnails")
data class ThumbnailEntity(
    @PrimaryKey val videoPath: String,
    val serverId: Long?,
    val thumbnailPath: String,
    val lastUpdated: Long = System.currentTimeMillis()
)
