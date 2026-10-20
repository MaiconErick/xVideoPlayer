package com.app.xvideoplayer.data.model

import org.json.JSONObject

/** Picture adjustments, each -1..1 (vignette and softness 0..1). All zero = no shader at all. */
data class VideoFilters(
    val brightness: Float = 0f, val contrast: Float = 0f, val saturation: Float = 0f,
    val highlights: Float = 0f, val blacks: Float = 0f, val whites: Float = 0f,
    val vignette: Float = 0f, val vignetteSoft: Float = 0.5f
) {
    val isDefault: Boolean get() = this == VideoFilters() || (copy(vignetteSoft = 0.5f) == VideoFilters() )

    fun toJson(): String = JSONObject().put("b", brightness.toDouble()).put("c", contrast.toDouble()).put("s", saturation.toDouble())
        .put("h", highlights.toDouble()).put("k", blacks.toDouble()).put("w", whites.toDouble()).put("v", vignette.toDouble()).put("vs", vignetteSoft.toDouble()).toString()

    companion object {
        fun parse(json: String?): VideoFilters? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val o = JSONObject(json)
                VideoFilters(o.optDouble("b").toFloat(), o.optDouble("c").toFloat(), o.optDouble("s").toFloat(), o.optDouble("h").toFloat(),
                    o.optDouble("k").toFloat(), o.optDouble("w").toFloat(), o.optDouble("v").toFloat(), o.optDouble("vs", 0.5).toFloat())
            }.getOrNull()
        }
    }
}
