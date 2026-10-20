package com.app.xvideoplayer.data.model

import org.json.JSONArray
import org.json.JSONObject

/** What a double tap inside a zone does. */
enum class ZoneAction(val label: String, val usesSeconds: Boolean = false) {
    NONE("Nothing"),
    SEEK_BACK("Seek back", true),
    SEEK_FORWARD("Seek forward", true),
    PLAY_PAUSE("Play / pause"),
    PREVIOUS("Previous video"),
    NEXT("Next video"),
    CONTROLS("Show / hide controls"),
    MUTE("Mute"),
    SCREENSHOT("Screenshot"),
    REPLAY_SUBS("Replay with subtitles"),
    SUBTITLES("Subtitles on / off"),
    SPEED_UP("Faster"),
    SPEED_DOWN("Slower"),
    LOCK("Lock controls")
}

/** A rectangle on the video, in fractions of its width and height, with the action it triggers. */
data class TapZone(
    val id: String, val x0: Float, val y0: Float, val x1: Float, val y1: Float,
    val action: ZoneAction, val seconds: Int = 10, val color: Long
) {
    fun contains(x: Float, y: Float) = x in x0..x1 && y in y0..y1

    companion object {
        val PALETTE = listOf(0xFF4FC3F7, 0xFF81C784, 0xFFFFB74D, 0xFFE57373, 0xFFBA68C8, 0xFFFFF176, 0xFF4DB6AC, 0xFFF06292)

        /** The classic layout: left third back, right third forward, middle pause. */
        fun defaults(seconds: Int = 10) = listOf(
            TapZone("left", 0f, 0f, 1f / 3f, 1f, ZoneAction.SEEK_BACK, seconds, PALETTE[0]),
            TapZone("mid", 1f / 3f, 0f, 2f / 3f, 1f, ZoneAction.PLAY_PAUSE, seconds, PALETTE[1]),
            TapZone("right", 2f / 3f, 0f, 1f, 1f, ZoneAction.SEEK_FORWARD, seconds, PALETTE[2])
        )

        fun parse(json: String, fallbackSeconds: Int = 10): List<TapZone> {
            if (json.isBlank()) return defaults(fallbackSeconds)
            return runCatching {
                val a = JSONArray(json)
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    TapZone(
                        o.getString("id"), o.getDouble("x0").toFloat(), o.getDouble("y0").toFloat(), o.getDouble("x1").toFloat(), o.getDouble("y1").toFloat(),
                        runCatching { ZoneAction.valueOf(o.getString("action")) }.getOrDefault(ZoneAction.NONE), o.optInt("seconds", 10), o.optLong("color", PALETTE[0])
                    )
                }
            }.getOrNull() ?: defaults(fallbackSeconds)
        }

        fun encode(zones: List<TapZone>): String = JSONArray().also { a ->
            zones.forEach { z ->
                a.put(JSONObject().put("id", z.id).put("x0", z.x0.toDouble()).put("y0", z.y0.toDouble()).put("x1", z.x1.toDouble()).put("y1", z.y1.toDouble())
                    .put("action", z.action.name).put("seconds", z.seconds).put("color", z.color))
            }
        }.toString()
    }
}
