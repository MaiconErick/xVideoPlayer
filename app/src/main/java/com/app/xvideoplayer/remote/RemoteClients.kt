package com.app.xvideoplayer.remote

import android.content.Context
import android.net.Uri
import com.app.xvideoplayer.data.model.ServerConfig
import com.app.xvideoplayer.data.model.ServerType
import com.app.xvideoplayer.data.model.VideoItem
import com.app.xvideoplayer.data.model.VideoSource
import dagger.hilt.android.qualifiers.ApplicationContext
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton

/** Represents a remote file entry before it becomes a VideoItem */
data class RemoteFileEntry(
    val name: String,
    val path: String,
    val size: Long,
    val isDirectory: Boolean,
    val lastModified: Long
)

private val VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v",
    "ts", "mts", "m2ts", "3gp", "3g2", "ogv", "mpg", "mpeg", "vob"
)

fun String.isVideoFile(): Boolean {
    val ext = substringAfterLast('.', "").lowercase()
    return ext in VIDEO_EXTENSIONS
}

@Singleton
class SmbClient @Inject constructor(@ApplicationContext private val context: Context) {

    fun createContext(server: ServerConfig): CIFSContext {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.responseTimeout", "30000")
            setProperty("jcifs.smb.client.soTimeout", "35000")
            // jcifs defaults to 64 KB per request, which is latency-bound on wifi; SMB2/3 can do 1 MB
            setProperty("jcifs.smb.client.rcv_buf_size", "1048576")
            setProperty("jcifs.smb.client.snd_buf_size", "1048576")
            setProperty("jcifs.smb.client.useLargeReadWrite", "true")
        }
        val baseContext = BaseContext(PropertyConfiguration(props))
        return if (server.username.isNotBlank()) {
            baseContext.withCredentials(
                NtlmPasswordAuthenticator(null, server.username, server.password)
            )
        } else {
            baseContext.withGuestCrendentials()
        }
    }

    suspend fun listFiles(server: ServerConfig, path: String = server.remotePath): List<RemoteFileEntry> =
        withContext(Dispatchers.IO) {
            val ctx = createContext(server)
            val smbUrl = "smb://${server.host}:${server.port}${path.ensureTrailingSlash()}"
            val smbDir = SmbFile(smbUrl, ctx)
            smbDir.listFiles()?.mapNotNull { file ->
                if (file.isDirectory || file.name.isVideoFile()) {
                    RemoteFileEntry(
                        name = file.name.trimEnd('/'),
                        path = file.path.removePrefix("smb://${server.host}:${server.port}"),
                        size = if (file.isDirectory) 0L else file.length(),
                        isDirectory = file.isDirectory,
                        lastModified = file.lastModified()
                    )
                } else null
            }?.sortedWith(compareByDescending<RemoteFileEntry> { it.isDirectory }.thenBy { it.name })
                ?: emptyList()
        }

    suspend fun getInputStream(server: ServerConfig, path: String): InputStream =
        withContext(Dispatchers.IO) {
            val ctx = createContext(server)
            val smbUrl = "smb://${server.host}:${server.port}$path"
            SmbFile(smbUrl, ctx).inputStream
        }

    fun getSmbUri(server: ServerConfig, path: String): String {
        return "smb://${server.host}:${server.port}$path"
    }

    suspend fun downloadFile(server: ServerConfig, remotePath: String, localFile: File): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val input = getInputStream(server, remotePath)
                input.use { ins ->
                    FileOutputStream(localFile).use { out ->
                        ins.copyTo(out, bufferSize = 8192)
                    }
                }
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
}

@Singleton
class FtpClient @Inject constructor(@ApplicationContext private val context: Context) {

    private fun createClient(server: ServerConfig): FTPClient {
        return FTPClient().apply {
            connectTimeout = 15000
            defaultTimeout = 30000
            connect(server.host, server.port)
            if (server.username.isNotBlank()) {
                login(server.username, server.password)
            } else {
                login("anonymous", "")
            }
            enterLocalPassiveMode()
            setFileType(FTP.BINARY_FILE_TYPE)
        }
    }

    suspend fun listFiles(server: ServerConfig, path: String = server.remotePath): List<RemoteFileEntry> =
        withContext(Dispatchers.IO) {
            val ftp = createClient(server)
            try {
                ftp.listFiles(path)?.mapNotNull { file ->
                    if (file.isDirectory || file.name.isVideoFile()) {
                        RemoteFileEntry(
                            name = file.name,
                            path = "${path.ensureTrailingSlash()}${file.name}",
                            size = file.size,
                            isDirectory = file.isDirectory,
                            lastModified = file.timestamp?.timeInMillis ?: 0L
                        )
                    } else null
                }?.sortedWith(compareByDescending<RemoteFileEntry> { it.isDirectory }.thenBy { it.name })
                    ?: emptyList()
            } finally {
                try { ftp.disconnect() } catch (_: Exception) {}
            }
        }

    suspend fun getInputStream(server: ServerConfig, path: String): Pair<FTPClient, InputStream> =
        withContext(Dispatchers.IO) {
            val ftp = createClient(server)
            val stream = ftp.retrieveFileStream(path) ?: throw Exception("Failed to open FTP stream: $path")
            ftp to stream
        }

    suspend fun downloadFile(server: ServerConfig, remotePath: String, localFile: File): Boolean =
        withContext(Dispatchers.IO) {
            val ftp = createClient(server)
            try {
                FileOutputStream(localFile).use { out ->
                    ftp.retrieveFile(remotePath, out)
                }
            } finally {
                try { ftp.disconnect() } catch (_: Exception) {}
            }
        }
}

@Singleton
class HttpVideoClient @Inject constructor() {
    /** For direct URL sources, just validate and return the URI */
    fun getVideoUri(url: String): Uri = Uri.parse(url)

    suspend fun testConnection(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 10000
            connection.connect()
            val code = connection.responseCode
            connection.disconnect()
            code in 200..299
        } catch (e: Exception) {
            false
        }
    }
}

private fun String.ensureTrailingSlash(): String =
    if (endsWith("/")) this else "$this/"
