package com.app.xvideoplayer.util

import java.text.Normalizer

/** Accent/case-insensitive "every word appears somewhere" matcher (typing `jao` finds `Jão`). */
object SearchMatch {
    private val marks = Regex("[\\p{Mn}&&[^\\u3099\\u309A]]+") // keep Japanese (han)dakuten: がっこう ≠ かっこう

    fun normalize(s: String): String =
        marks.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase()
            .replace('_', ' ').replace('.', ' ').replace('-', ' ')

    fun matches(query: String, vararg fields: String): Boolean {
        val words = normalize(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return true
        val hay = fields.joinToString(" ") { normalize(it) }
        return words.all { hay.contains(it) }
    }
}
