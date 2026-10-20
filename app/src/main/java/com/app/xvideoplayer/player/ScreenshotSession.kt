package com.app.xvideoplayer.player

import android.content.Context
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One screenshot waiting to be combined into a collage: a clean frame (no burned-in subtitles)
 * plus the subtitle lines known at that moment, so the editor can re-draw any of them on it.
 * [lineIndex] = -1 means "no subtitle"; [customText] overrides the chosen line when set.
 */
data class CollageShot(
    val id: Long,
    val file: File,
    val width: Int,
    val height: Int,
    val positionMs: Long,
    val lines: List<SubLine>,
    val lineIndex: Int,
    val customText: String? = null
) {
    val text: String get() = customText ?: lines.getOrNull(lineIndex)?.text.orEmpty()
}

/** Screenshots collected for a collage; survives leaving the player for the editor and back. */
@Singleton
class ScreenshotSession @Inject constructor(@ApplicationContext private val context: Context) {

    private val dir get() = File(context.cacheDir, "collage").apply { mkdirs() }

    private val _shots = MutableStateFlow<List<CollageShot>>(emptyList())
    val shots: StateFlow<List<CollageShot>> = _shots.asStateFlow()

    var videoTitle: String = ""

    suspend fun add(frame: Bitmap, positionMs: Long, lines: List<SubLine>, currentText: String, title: String) = withContext(Dispatchers.IO) {
        videoTitle = title
        val id = System.nanoTime()
        val file = File(dir, "shot_$id.jpg")
        file.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        // the line on screen when captured: the last one that started at or before the frame
        val idx = if (currentText.isBlank()) -1
            else lines.indexOfLast { it.startMs <= positionMs + 50 && it.text == currentText }
                .takeIf { it >= 0 } ?: lines.indexOfLast { it.startMs <= positionMs + 50 }
        _shots.update { it + CollageShot(id, file, frame.width, frame.height, positionMs, lines, idx) }
        frame.recycle()
    }

    fun setLine(id: Long, index: Int) = _shots.update { l -> l.map { if (it.id == id) it.copy(lineIndex = index, customText = null) else it } }
    fun setCustomText(id: Long, text: String?) = _shots.update { l -> l.map { if (it.id == id) it.copy(customText = text) else it } }
    fun remove(id: Long) = _shots.update { l -> l.filterNot { it.id == id }.also { _ -> l.firstOrNull { it.id == id }?.file?.delete() } }
    fun move(id: Long, delta: Int) = _shots.update { l ->
        val i = l.indexOfFirst { it.id == id }; val j = i + delta
        if (i < 0 || j !in l.indices) l else l.toMutableList().apply { add(j, removeAt(i)) }
    }

    fun clear() {
        _shots.value.forEach { it.file.delete() }
        _shots.value = emptyList()
    }
}
