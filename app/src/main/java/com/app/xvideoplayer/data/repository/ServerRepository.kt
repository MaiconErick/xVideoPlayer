package com.app.xvideoplayer.data.repository

import com.app.xvideoplayer.data.db.dao.ServerDao
import com.app.xvideoplayer.data.db.dao.ThumbnailDao
import com.app.xvideoplayer.data.db.entity.ServerEntity
import com.app.xvideoplayer.data.db.entity.ThumbnailEntity
import com.app.xvideoplayer.data.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerRepository @Inject constructor(
    private val serverDao: ServerDao,
    private val thumbnailDao: ThumbnailDao
) {
    fun getAllServers(): Flow<List<ServerConfig>> =
        serverDao.getAllServers().map { list -> list.map { it.toConfig() } }

    fun getActiveServers(): Flow<List<ServerConfig>> =
        serverDao.getActiveServers().map { list -> list.map { it.toConfig() } }

    suspend fun getServerById(id: Long): ServerConfig? = serverDao.getServerById(id)?.toConfig()

    /** Resolves the saved credentials for an smb://host:port/... link */
    suspend fun findServer(type: ServerType, host: String, port: Int): ServerConfig? =
        getAllServers().first().firstOrNull { it.type == type && it.host.equals(host, true) && it.port == port }

    suspend fun insertServer(server: ServerConfig): Long = serverDao.insertServer(server.toEntity())

    suspend fun updateServer(server: ServerConfig) = serverDao.updateServer(server.toEntity())

    suspend fun deleteServer(server: ServerConfig) {
        serverDao.deleteServer(server.toEntity())
        thumbnailDao.deleteThumbnailsByServer(server.id)
    }

    suspend fun updateLastConnected(id: Long) = serverDao.updateLastConnected(id)

    // Thumbnail cache for remote videos
    suspend fun getCachedThumbnail(videoPath: String): String? =
        thumbnailDao.getThumbnail(videoPath)?.thumbnailPath

    suspend fun cacheThumbnail(videoPath: String, serverId: Long?, localPath: String) {
        thumbnailDao.insertThumbnail(
            ThumbnailEntity(videoPath = videoPath, serverId = serverId, thumbnailPath = localPath)
        )
    }

    fun getThumbnailsByServer(serverId: Long) = thumbnailDao.getThumbnailsByServer(serverId)

    private fun ServerEntity.toConfig() = ServerConfig(
        id = id, name = name, type = ServerType.valueOf(type),
        host = host, port = port, username = username, password = password,
        remotePath = remotePath, streamMode = StreamMode.valueOf(streamMode),
        isActive = isActive, lastConnected = lastConnected
    )

    private fun ServerConfig.toEntity() = ServerEntity(
        id = id, name = name, type = type.name,
        host = host, port = port, username = username, password = password,
        remotePath = remotePath, streamMode = streamMode.name,
        isActive = isActive, lastConnected = lastConnected
    )
}
