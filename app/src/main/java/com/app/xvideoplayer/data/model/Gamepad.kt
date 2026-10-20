package com.app.xvideoplayer.data.model

import android.view.KeyEvent
import org.json.JSONObject

enum class PadTab(val label: String) { PLAYBACK("Playback"), PICTURE("Audio & screen"), SUBTITLES("Subtitles"), INTERFACE("Interface") }

/** Everything a controller button can be assigned to. */
enum class PadFeature(val tab: PadTab, val label: String) {
    PLAY_PAUSE(PadTab.PLAYBACK, "Play / pause"),
    SEEK_BACK(PadTab.PLAYBACK, "Seek back"),
    SEEK_FORWARD(PadTab.PLAYBACK, "Seek forward"),
    PREVIOUS(PadTab.PLAYBACK, "Previous video"),
    NEXT(PadTab.PLAYBACK, "Next video"),
    SPEED_UP(PadTab.PLAYBACK, "Faster"),
    SPEED_DOWN(PadTab.PLAYBACK, "Slower"),
    FRAME_BACK(PadTab.PLAYBACK, "Frame back"),
    FRAME_FORWARD(PadTab.PLAYBACK, "Frame forward"),
    VOLUME_UP(PadTab.PICTURE, "Volume up"),
    VOLUME_DOWN(PadTab.PICTURE, "Volume down"),
    MUTE(PadTab.PICTURE, "Mute"),
    BRIGHTNESS_UP(PadTab.PICTURE, "Brightness up"),
    BRIGHTNESS_DOWN(PadTab.PICTURE, "Brightness down"),
    ZOOM_MODE(PadTab.PICTURE, "Fit / crop / stretch"),
    ROTATE(PadTab.PICTURE, "Rotation"),
    SUB_TOGGLE(PadTab.SUBTITLES, "Subtitles on / off"),
    SUB_TRACK(PadTab.SUBTITLES, "Next subtitle track"),
    SUB_NEXT_LINE(PadTab.SUBTITLES, "Next subtitle line"),
    SUB_PREV_LINE(PadTab.SUBTITLES, "Previous subtitle line"),
    SUB_UP(PadTab.SUBTITLES, "Subtitles higher"),
    SUB_DOWN(PadTab.SUBTITLES, "Subtitles lower"),
    REPLAY_SUBS(PadTab.SUBTITLES, "Replay with subtitles"),
    LOOKUP(PadTab.SUBTITLES, "Word lookup"),
    CONTROLS(PadTab.INTERFACE, "Show / hide controls"),
    SCREENSHOT(PadTab.INTERFACE, "Screenshot"),
    LOCK(PadTab.INTERFACE, "Lock controls"),
    BACK(PadTab.INTERFACE, "Leave the player")
}

/** Button → feature table. Stored as JSON {"FEATURE": keyCode}. */
object GamepadMap {
    val DEFAULT: Map<PadFeature, Int> = mapOf(
        PadFeature.PLAY_PAUSE to KeyEvent.KEYCODE_BUTTON_A,
        PadFeature.BACK to KeyEvent.KEYCODE_BUTTON_B,
        PadFeature.SUB_TOGGLE to KeyEvent.KEYCODE_BUTTON_X,
        PadFeature.CONTROLS to KeyEvent.KEYCODE_BUTTON_Y,
        PadFeature.SEEK_BACK to KeyEvent.KEYCODE_DPAD_LEFT,
        PadFeature.SEEK_FORWARD to KeyEvent.KEYCODE_DPAD_RIGHT,
        PadFeature.VOLUME_UP to KeyEvent.KEYCODE_DPAD_UP,
        PadFeature.VOLUME_DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
        PadFeature.PREVIOUS to KeyEvent.KEYCODE_BUTTON_L1,
        PadFeature.NEXT to KeyEvent.KEYCODE_BUTTON_R1,
        PadFeature.SPEED_DOWN to KeyEvent.KEYCODE_BUTTON_L2,
        PadFeature.SPEED_UP to KeyEvent.KEYCODE_BUTTON_R2,
        PadFeature.SCREENSHOT to KeyEvent.KEYCODE_BUTTON_SELECT,
        PadFeature.LOCK to KeyEvent.KEYCODE_BUTTON_START
    )

    fun parse(json: String): Map<PadFeature, Int> {
        if (json.isBlank()) return DEFAULT
        return runCatching {
            val o = JSONObject(json)
            buildMap { PadFeature.entries.forEach { f -> if (o.has(f.name)) { val k = o.getInt(f.name); if (k > 0) put(f, k) } } }
        }.getOrNull() ?: DEFAULT
    }

    fun encode(map: Map<PadFeature, Int>): String = JSONObject().also { o -> PadFeature.entries.forEach { f -> o.put(f.name, map[f] ?: 0) } }.toString()

    /** "A", "L1", "D-pad left"… */
    fun keyName(code: Int): String = when (code) {
        KeyEvent.KEYCODE_BUTTON_A -> "A"; KeyEvent.KEYCODE_BUTTON_B -> "B"; KeyEvent.KEYCODE_BUTTON_X -> "X"; KeyEvent.KEYCODE_BUTTON_Y -> "Y"
        KeyEvent.KEYCODE_BUTTON_L1 -> "L1"; KeyEvent.KEYCODE_BUTTON_R1 -> "R1"; KeyEvent.KEYCODE_BUTTON_L2 -> "L2"; KeyEvent.KEYCODE_BUTTON_R2 -> "R2"
        KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3"; KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3"
        KeyEvent.KEYCODE_BUTTON_SELECT -> "Select"; KeyEvent.KEYCODE_BUTTON_START -> "Start"; KeyEvent.KEYCODE_BUTTON_MODE -> "Mode"
        KeyEvent.KEYCODE_DPAD_LEFT -> "D-pad ←"; KeyEvent.KEYCODE_DPAD_RIGHT -> "D-pad →"; KeyEvent.KEYCODE_DPAD_UP -> "D-pad ↑"; KeyEvent.KEYCODE_DPAD_DOWN -> "D-pad ↓"
        KeyEvent.KEYCODE_DPAD_CENTER -> "D-pad center"
        else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
    }

    fun isPadKey(e: KeyEvent): Boolean =
        e.keyCode in KeyEvent.KEYCODE_BUTTON_A..KeyEvent.KEYCODE_BUTTON_MODE ||
            ((e.source and android.view.InputDevice.SOURCE_DPAD) == android.view.InputDevice.SOURCE_DPAD && e.keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER) ||
            ((e.source and android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD && e.keyCode != KeyEvent.KEYCODE_BACK)
}

/**
 * Receives controller key events from MainActivity. While a settings dialog is listening ([capture]) the next
 * button press is handed to it; otherwise the player's [handler] runs the mapped feature.
 */
object GamepadRouter {
    @Volatile var map: Map<Int, PadFeature> = emptyMap()
    @Volatile var handler: ((PadFeature) -> Unit)? = null
    @Volatile var capture: ((Int) -> Unit)? = null

    fun setMapping(m: Map<PadFeature, Int>) { map = m.entries.groupBy({ it.value }, { it.key }).mapValues { it.value.first() } }

    /** true = consumed */
    fun onKey(e: KeyEvent): Boolean {
        if (!GamepadMap.isPadKey(e)) return false
        capture?.let { c -> if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) c(e.keyCode); return true }
        val h = handler ?: return false
        val f = map[e.keyCode] ?: return false
        if (e.action == KeyEvent.ACTION_DOWN) h(f)   // held buttons repeat, which makes seek/volume smooth
        return true
    }
}
