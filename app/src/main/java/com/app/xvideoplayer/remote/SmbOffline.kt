package com.app.xvideoplayer.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.app.xvideoplayer.data.model.ServerConfig
import com.app.xvideoplayer.data.repository.ServerRepository
import com.app.xvideoplayer.util.formatFileSize
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers every SMB/FTP folder listing on disk, so a folder opens instantly (and still opens
 * when the server is off) while the real listing loads in the background.
 */
@Singleton
class RemoteListingCache @Inject constructor(@ApplicationContext private val context: Context) {
    data class Cached(val entries: List<RemoteFileEntry>, val savedAt: Long)

    private fun fileFor(serverId: Long, path: String) =
        File(File(context.filesDir, "listing_cache/$serverId").apply { mkdirs() }, sha1(path).take(24) + ".json")

    suspend fun load(serverId: Long, path: String): Cached? = withContext(Dispatchers.IO) {
        runCatching {
            val f = fileFor(serverId, path)
            if (!f.exists()) return@runCatching null
            val o = JSONObject(f.readText())
            val arr = o.getJSONArray("entries")
            Cached(
                (0 until arr.length()).map { i ->
                    val e = arr.getJSONObject(i)
                    RemoteFileEntry(e.getString("name"), e.getString("path"), e.optLong("size"), e.optBoolean("dir"), e.optLong("modified"))
                },
                o.optLong("savedAt")
            )
        }.getOrNull()
    }

    suspend fun save(serverId: Long, path: String, entries: List<RemoteFileEntry>) = withContext(Dispatchers.IO) {
        runCatching {
            val arr = JSONArray()
            entries.forEach { e ->
                arr.put(JSONObject().put("name", e.name).put("path", e.path).put("size", e.size).put("dir", e.isDirectory).put("modified", e.lastModified))
            }
            val f = fileFor(serverId, path)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(JSONObject().put("path", path).put("savedAt", System.currentTimeMillis()).put("entries", arr).toString())
            tmp.renameTo(f)
        }
    }

    fun clear(serverId: Long) { File(context.filesDir, "listing_cache/$serverId").deleteRecursively() }
}

internal fun sha1(s: String): String =
    MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

/**
 * Whole-file offline copies of SMB videos. One file at a time (SMB is fastest that way), resumable
 * from the partial file, kept in the app's external files dir. The player uses a finished copy
 * instead of streaming ([completeFileFor]).
 */
@Singleton
class SmbDownloads @Inject constructor(
    @ApplicationContext private val context: Context,
    private val smbClient: SmbClient,
    private val serverRepository: ServerRepository
) {
    enum class State { QUEUED, RUNNING, DONE, FAILED }

    data class Item(
        val uri: String, val serverId: Long, val path: String, val name: String, val size: Long,
        val done: Long = 0, val state: State = State.QUEUED, val error: String? = null,
        val speed: Long = 0, val addedAt: Long = System.currentTimeMillis()
    ) {
        val fraction get() = if (size > 0) (done.toFloat() / size).coerceIn(0f, 1f) else 0f
    }

    /** Everything the current run covers: finished ones stay in it until the queue drains, so the overall bar keeps counting. */
    data class Overall(val active: Boolean, val count: Int, val finished: Int, val failed: Int, val total: Long, val done: Long, val speed: Long, val current: String?) {
        val fraction get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
        val etaSeconds get() = if (speed > 0) (total - done) / speed else -1L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()
    private val batch = MutableStateFlow<Set<String>>(emptySet())
    private var worker: Job? = null
    private var running: String? = null

    val overall: StateFlow<Overall> = combine(_items, batch) { list, run ->
        val inRun = list.filter { it.uri in run }
        Overall(
            active = inRun.any { it.state == State.QUEUED || it.state == State.RUNNING },
            count = inRun.size, finished = inRun.count { it.state == State.DONE }, failed = inRun.count { it.state == State.FAILED },
            total = inRun.sumOf { it.size }, done = inRun.sumOf { if (it.state == State.DONE) it.size else it.done },
            speed = inRun.firstOrNull { it.state == State.RUNNING }?.speed ?: 0, current = inRun.firstOrNull { it.state == State.RUNNING }?.name
        )
    }.stateIn(scope, SharingStarted.Eagerly, Overall(false, 0, 0, 0, 0, 0, 0, null))

    private val dir: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "offline").apply { mkdirs() }
    private val indexFile get() = File(context.filesDir, "offline_index.json")

    init {
        scope.launch {
            // a run that was interrupted (app killed) goes back to the queue but waits for the user to resume it
            _items.value = readIndex().map { if (it.state == State.RUNNING || it.state == State.QUEUED) it.copy(state = State.FAILED, error = "Interrupted", speed = 0) else it }
        }
    }

    fun localFile(item: Item): File = File(dir, sha1(item.uri).take(16) + "." + item.name.substringAfterLast('.', "mp4").lowercase())
    private fun partFile(item: Item) = File(localFile(item).path + ".part")

    /** The finished offline copy of an smb:// link, if there is one */
    fun completeFileFor(uri: String): File? {
        val it = _items.value.firstOrNull { i -> i.uri == uri && i.state == State.DONE } ?: return null
        return localFile(it).takeIf { f -> f.exists() && (it.size <= 0 || f.length() == it.size) }
    }

    fun enqueue(server: ServerConfig, entries: List<RemoteFileEntry>) {
        val wasIdle = !overall.value.active
        val now = System.currentTimeMillis()
        val added = mutableListOf<String>()
        _items.update { cur ->
            val byUri = cur.associateBy { it.uri }.toMutableMap()
            entries.filter { !it.isDirectory }.forEachIndexed { i, e ->
                val uri = smbClient.getSmbUri(server, e.path)
                val old = byUri[uri]
                if (old?.state == State.DONE && completeFileFor(uri) != null) return@forEachIndexed
                if (old?.state == State.QUEUED || old?.state == State.RUNNING) return@forEachIndexed
                byUri[uri] = Item(uri, server.id, e.path, e.name, e.size, done = old?.done ?: 0, addedAt = now + i)
                added += uri
            }
            byUri.values.sortedBy { it.addedAt }
        }
        batch.update { if (wasIdle) added.toSet() else it + added }
        persist()
        start()
    }

    /** Puts failed items back in the queue */
    fun retry(uri: String? = null) {
        val keys = _items.value.filter { it.state == State.FAILED && (uri == null || it.uri == uri) }.map { it.uri }
        if (keys.isEmpty()) return
        val wasIdle = !overall.value.active
        batch.update { if (wasIdle) keys.toSet() else it + keys }
        _items.update { l -> l.map { if (it.uri in keys) it.copy(state = State.QUEUED, error = null) else it } }
        persist(); start()
    }

    /** Stops (if running) and forgets a download, deleting the partial or finished file */
    fun remove(uris: Collection<String>) {
        val set = uris.toSet()
        val gone = _items.value.filter { it.uri in set }
        _items.update { l -> l.filterNot { it.uri in set } }
        batch.update { it - set }
        gone.forEach { localFile(it).delete(); partFile(it).delete() }
        if (running in set) { worker?.cancel(); worker = null; running = null; start() }
        persist()
    }

    fun cancelAll() {
        remove(_items.value.filter { it.state == State.QUEUED || it.state == State.RUNNING || it.state == State.FAILED }.map { it.uri })
    }

    private fun start() {
        if (worker?.isActive == true) return
        if (_items.value.none { it.state == State.QUEUED }) return
        SmbDownloadService.start(context)
        worker = scope.launch {
            while (isActive) {
                val next = _items.value.firstOrNull { it.state == State.QUEUED } ?: break
                running = next.uri
                runOne(next)
                running = null
            }
        }
    }

    private suspend fun runOne(item: Item) {
        update(item.uri) { it.copy(state = State.RUNNING, error = null) }
        try {
            val server = serverRepository.getServerById(item.serverId) ?: error("The server was removed")
            val part = partFile(item)
            val file = SmbFile(item.uri, smbClient.createContext(server))
            val length = file.length().takeIf { it > 0 } ?: item.size
            if (length != item.size) update(item.uri) { it.copy(size = length) }
            var pos = if (part.exists() && part.length() <= length) part.length() else 0L
            RandomAccessFile(part, "rw").use { out ->
                out.setLength(pos); out.seek(pos)
                SmbRandomAccessFile(file, "r").use { raf ->
                    raf.seek(pos)
                    val buf = ByteArray(1 shl 20)
                    var lastUi = SystemClock.elapsedRealtime()
                    var windowStart = lastUi; var windowBytes = 0L; var speed = 0L
                    while (pos < length) {
                        currentCoroutineContext().ensureActive()
                        val n = raf.read(buf, 0, minOf(buf.size.toLong(), length - pos).toInt())
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        pos += n; windowBytes += n
                        val now = SystemClock.elapsedRealtime()
                        if (now - windowStart >= 1000) { speed = windowBytes * 1000 / (now - windowStart); windowStart = now; windowBytes = 0 }
                        if (now - lastUi >= 300) { lastUi = now; val p = pos; val sp = speed; update(item.uri) { it.copy(done = p, speed = sp) } }
                    }
                }
            }
            if (pos < length) error("Connection closed early")
            val dest = localFile(item)
            dest.delete()
            if (!part.renameTo(dest)) error("Couldn't save the file")
            update(item.uri) { it.copy(done = length, size = length, state = State.DONE, speed = 0) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            update(item.uri) { it.copy(state = State.FAILED, error = e.message ?: e.javaClass.simpleName, speed = 0) }
        } finally {
            persist()
        }
    }

    private fun update(uri: String, f: (Item) -> Item) = _items.update { l -> l.map { if (it.uri == uri) f(it) else it } }

    private fun persist() {
        val arr = JSONArray()
        _items.value.forEach { i ->
            arr.put(JSONObject().put("uri", i.uri).put("server", i.serverId).put("path", i.path).put("name", i.name).put("size", i.size)
                .put("done", i.done).put("state", i.state.name).put("error", i.error ?: "").put("added", i.addedAt))
        }
        runCatching { val tmp = File(indexFile.path + ".tmp"); tmp.writeText(arr.toString()); tmp.renameTo(indexFile) }
    }

    private fun readIndex(): List<Item> = runCatching {
        val arr = JSONArray(indexFile.readText())
        (0 until arr.length()).map { k ->
            val o = arr.getJSONObject(k)
            Item(
                o.getString("uri"), o.getLong("server"), o.getString("path"), o.getString("name"), o.getLong("size"),
                o.optLong("done"), runCatching { State.valueOf(o.getString("state")) }.getOrDefault(State.FAILED),
                o.optString("error").ifEmpty { null }, 0, o.optLong("added")
            )
        }.filter { it.state != State.DONE || localFile(it).exists() }
    }.getOrDefault(emptyList())
}

/**
 * Keeps downloads alive when the app is in the background and shows the overall progress
 * in a notification. Stops itself when the queue is empty.
 */
@OptIn(FlowPreview::class)
@AndroidEntryPoint
class SmbDownloadService : Service() {
    @Inject lateinit var downloads: SmbDownloads
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Offline copies", NotificationManager.IMPORTANCE_LOW))
        val first = build(downloads.overall.value)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, first)
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "xvp:offline").apply { setReferenceCounted(false); acquire() }
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xvp:offline").apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        }
        scope.coroutineContext.cancelChildren()
        val startedAt = SystemClock.elapsedRealtime()
        scope.launch {
            downloads.overall.sample(700).collect { o ->
                // the queue may not have reached the overall flow yet right after start
                if (!o.active && SystemClock.elapsedRealtime() - startedAt < 3000) return@collect
                if (!o.active) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    if (o.count > 0) nm.notify(ID_DONE, doneNotification(o))
                    stopSelf()
                } else nm.notify(ID, build(o))
            }
        }
        return START_NOT_STICKY
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, packageManager.getLaunchIntentForPackage(packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun build(o: SmbDownloads.Overall): Notification {
        val pct = (o.fraction * 100).toInt()
        val text = "${o.done.formatFileSize()} of ${o.total.formatFileSize()}" + if (o.speed > 0) "  ·  ${o.speed.formatFileSize()}/s" else ""
        return Notification.Builder(this).apply {
            if (Build.VERSION.SDK_INT >= 26) setChannelId(CHANNEL)
            setSmallIcon(android.R.drawable.stat_sys_download)
            setContentTitle("Caching ${minOf(o.finished + 1, o.count)} of ${o.count}  ·  $pct%")
            setContentText(o.current?.let { "$it\n$text" } ?: text)
            setStyle(Notification.BigTextStyle().bigText(listOfNotNull(o.current, text).joinToString("\n")))
            setProgress(1000, (o.fraction * 1000).toInt(), o.total <= 0)
            setOngoing(true); setOnlyAlertOnce(true); setContentIntent(openApp())
        }.build()
    }

    private fun doneNotification(o: SmbDownloads.Overall) = Notification.Builder(this).apply {
        if (Build.VERSION.SDK_INT >= 26) setChannelId(CHANNEL)
        setSmallIcon(android.R.drawable.stat_sys_download_done)
        setContentTitle(if (o.failed > 0) "${o.finished} cached, ${o.failed} failed" else "${o.finished} video${if (o.finished == 1) "" else "s"} cached")
        setContentText("${o.total.formatFileSize()} ready to play offline")
        setAutoCancel(true); setContentIntent(openApp())
    }.build()

    override fun onDestroy() {
        scope.cancel()
        runCatching { wifiLock?.release() }; runCatching { wakeLock?.release() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "offline_copies"
        private const val ID = 4210
        private const val ID_DONE = 4211
        fun start(context: Context) {
            val i = Intent(context, SmbDownloadService::class.java)
            runCatching { if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i) }
        }
    }
}
