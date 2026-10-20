package com.app.xvideoplayer.player

import android.net.Uri
import com.app.xvideoplayer.data.model.ServerConfig
import com.app.xvideoplayer.remote.SmbClient
import jcifs.CIFSContext
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * mpv's FFmpeg build has no SMB support, so SMB files are served to it over a tiny
 * loopback HTTP server with Range support (FFmpeg opens a new request for every seek).
 */
@Singleton
class SmbHttpBridge @Inject constructor(private val smbClient: SmbClient) {

    private class Route(val ctx: CIFSContext, val url: String)

    private val routes = ConcurrentHashMap<String, Route>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "smb-bridge").apply { isDaemon = true } }
    private var serverSocket: ServerSocket? = null

    /** Returns an http://127.0.0.1 URL that streams [smbUrl] using [server]'s credentials. */
    fun urlFor(server: ServerConfig, smbUrl: String): String {
        val port = ensureStarted()
        val token = UUID.randomUUID().toString().replace("-", "")
        routes[token] = Route(smbClient.createContext(server), smbUrl)
        // keep the file name at the end so FFmpeg can use the extension as a format hint
        return "http://127.0.0.1:$port/$token/${Uri.encode(smbUrl.substringAfterLast('/'))}"
    }

    @Synchronized
    private fun ensureStarted(): Int {
        serverSocket?.takeIf { !it.isClosed }?.let { return it.localPort }
        val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        pool.execute {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (e: IOException) { break }
                pool.execute { handle(client) }
            }
        }
        return socket.localPort
    }

    private fun handle(client: Socket) = client.use { sock ->
        try {
            val input = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = input.readLine() ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0) ?: return
            val token = parts.getOrNull(1)?.trimStart('/')?.substringBefore('/') ?: return
            val out = BufferedOutputStream(sock.getOutputStream(), 64 * 1024)
            val route = routes[token] ?: run {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return
            }

            val file = SmbFile(route.url, route.ctx)
            val length = file.length()
            var start = 0L
            var end = length - 1
            val range = headers["range"]
            if (range != null && range.startsWith("bytes=")) {
                val (s, e) = range.removePrefix("bytes=").substringBefore(',').split('-').let { it[0] to it.getOrElse(1) { "" } }
                if (s.isNotEmpty()) { start = s.toLong(); if (e.isNotEmpty()) end = minOf(e.toLong(), length - 1) }
                else if (e.isNotEmpty()) start = maxOf(0, length - e.toLong())
            }
            if (start >= length && length > 0) {
                out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$length\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return
            }
            val count = end - start + 1
            val head = buildString {
                append(if (range != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                append("Content-Type: application/octet-stream\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: $count\r\n")
                if (range != null) append("Content-Range: bytes $start-$end/$length\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            if (method == "HEAD") { out.flush(); return }

            // Read ahead on a second thread so the SMB round trips overlap with writing to mpv
            val queue = java.util.concurrent.ArrayBlockingQueue<ByteArray>(24)
            val done = ByteArray(0)
            val stopFlag = java.util.concurrent.atomic.AtomicBoolean(false)
            val reader = Thread {
                try {
                    SmbRandomAccessFile(file, "r").use { raf ->
                        raf.seek(start)
                        var remaining = count
                        while (remaining > 0 && !stopFlag.get()) {
                            val buf = ByteArray(minOf(1024 * 1024L, remaining).toInt())
                            var got = 0
                            while (got < buf.size) {
                                val n = raf.read(buf, got, buf.size - got)
                                if (n <= 0) break
                                got += n
                            }
                            if (got <= 0) break
                            remaining -= got
                            val chunk = if (got == buf.size) buf else buf.copyOf(got)
                            while (!stopFlag.get() && !queue.offer(chunk, 200, java.util.concurrent.TimeUnit.MILLISECONDS)) { }
                            if (got < buf.size) break
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    while (!queue.offer(done, 200, java.util.concurrent.TimeUnit.MILLISECONDS)) { if (stopFlag.get()) break }
                }
            }.apply { isDaemon = true; name = "smb-read"; this.start() }
            try {
                while (true) {
                    val chunk = queue.take()
                    if (chunk.isEmpty()) break
                    out.write(chunk, 0, chunk.size)
                }
            } finally { stopFlag.set(true); reader.interrupt() }
            out.flush()
        } catch (_: IOException) {
            // mpv closes the connection whenever it seeks — expected
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
