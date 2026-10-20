package com.app.xvideoplayer.ui.player

import com.app.xvideoplayer.player.TrackInfo

/** Chips shown at the top of the subtitles panel: key to label. */
val SUB_LANG_CHIPS = listOf("pt-br" to "PT-BR", "en" to "EN", "ko" to "KO", "ja" to "JA")

private fun codes(key: String) = when (key) {
    "pt-br" -> setOf("por", "pt", "pob", "ptbr", "pt-br", "pt_br")
    "en" -> setOf("eng", "en", "en-us", "en-gb")
    "ko" -> setOf("kor", "ko")
    "ja" -> setOf("jpn", "ja")
    else -> setOf(key)
}

/** First subtitle track in the wanted language, preferring full dialogue tracks over forced/SDH/signs ones. */
fun subtitleTrackFor(key: String, tracks: List<TrackInfo>): TrackInfo? {
    val c = codes(key)
    val hits = tracks.filter { t ->
        val lang = t.language?.lowercase().orEmpty()
        val label = t.label.lowercase()
        lang in c || (key == "pt-br" && ("brazil" in label || "brasil" in label || "portugu" in label)) ||
            (key == "en" && label.startsWith("english")) || (key == "ko" && "korean" in label) || (key == "ja" && "japan" in label)
    }
    fun score(t: TrackInfo): Int {
        val l = t.label.lowercase()
        var s = 0
        if (Regex("forced|signs|songs|commentary").containsMatchIn(l)) s += 10
        if ("sdh" in l || "hearing" in l) s += 3
        if (key == "pt-br" && !("brazil" in l || "brasil" in l || "br" == t.language?.lowercase()?.takeLast(2))) s += 1
        return s
    }
    return hits.minByOrNull { score(it) }
}
