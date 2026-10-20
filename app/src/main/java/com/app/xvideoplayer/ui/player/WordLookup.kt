package com.app.xvideoplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.xvideoplayer.ui.theme.*
import org.json.JSONObject

/** One tappable piece of a subtitle line: [text] is shown, [index] is its character offset in the line. */
private data class Tok(val text: String, val index: Int)

private fun isCjk(c: Char) = c in '぀'..'ヿ' || c in '㐀'..'鿿' || c in 'ｦ'..'ﾟ'

/** Spaced languages split into words; Japanese/Chinese have no spaces, so every character is tappable and the AI finds the word. */
private fun tokenize(line: String): List<Tok> {
    val out = mutableListOf<Tok>()
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            c.isWhitespace() || c == '\n' -> i++
            isCjk(c) -> { out += Tok(c.toString(), i); i++ }
            else -> {
                var j = i
                while (j < line.length && !line[j].isWhitespace() && !isCjk(line[j])) j++
                // "hurry!" / "-(sighs)" → look up the bare word; tokens that are only punctuation are skipped
                var a = i; var b = j
                while (a < b && !line[a].isLetterOrDigit()) a++
                while (b > a && !line[b - 1].isLetterOrDigit()) b--
                if (a < b) out += Tok(line.substring(a, b), a)
                i = j
            }
        }
    }
    return out
}

/**
 * Full-screen overlay over the paused video: the current subtitle line as tappable words;
 * tapping one asks the Pi AI for reading, meaning (English + Portuguese) and a usage note.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WordLookupOverlay(line: String, onClose: () -> Unit, lookup: suspend (sentence: String, index: Int, word: String) -> Result<JSONObject>) {
    val text = remember(line) { line.replace(Regex("<[^>]+>"), "").trim() }
    val toks = remember(text) { tokenize(text) }
    var picked by remember { mutableStateOf<Tok?>(null) }
    var result by remember { mutableStateOf<Result<JSONObject>?>(null) }
    LaunchedEffect(picked) {
        val t = picked ?: return@LaunchedEffect
        result = null
        result = lookup(text, t.index, t.text)
    }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).pointerInput(Unit) { detectTapGestures { onClose() } }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth(0.94f).heightIn(max = 520.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xF2131316))
                .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(22.dp)).pointerInput(Unit) { detectTapGestures { } }
                .padding(16.dp).verticalScroll(rememberScrollState())
        ) {
            Text("Tap a word", color = TextTertiary, fontSize = 11.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                toks.forEach { t ->
                    val on = picked == t
                    Text(
                        t.text, fontSize = 22.sp, color = if (on) OnAccent else Color.White,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) Accent else Color(0x1AFFFFFF)).clickable { picked = t }.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
            if (toks.isEmpty()) Text("No subtitle text at this moment.", color = TextSecondary, modifier = Modifier.padding(top = 8.dp))
            if (picked != null) {
                Spacer(Modifier.height(14.dp))
                val r = result
                when {
                    r == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp)); Text("Looking it up…", color = TextSecondary)
                    }
                    r.isFailure || r.getOrNull()?.has("error") == true ->
                        Text("Could not reach the AI server (${r.exceptionOrNull()?.message ?: r.getOrNull()?.optString("error")})", color = ErrorRed)
                    else -> LookupCard(r.getOrThrow())
                }
            }
        }
    }
}

@Composable
private fun LookupCard(o: JSONObject) {
    fun s(k: String) = o.optString(k).trim()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(s("word"), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Medium)
            if (s("reading").isNotBlank()) Text("  ${s("reading")}", color = AccentBright, fontSize = 18.sp)
        }
        val sub = listOf(s("romaji"), s("pos")).filter { it.isNotBlank() }.joinToString("  ·  ")
        if (sub.isNotBlank()) Text(sub, color = TextTertiary, fontSize = 13.sp)
        if (s("meaning_pt").isNotBlank()) Text("🇧🇷  ${s("meaning_pt")}", color = Color.White, fontSize = 18.sp)
        if (s("meaning_en").isNotBlank()) Text("🇬🇧  ${s("meaning_en")}", color = TextSecondary, fontSize = 15.sp)
        if (s("usage").isNotBlank()) Text(s("usage"), color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        if (s("example").isNotBlank()) Text(s("example"), color = TextTertiary, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
    }
}
