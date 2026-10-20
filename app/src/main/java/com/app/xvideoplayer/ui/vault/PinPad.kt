package com.app.xvideoplayer.ui.vault

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.xvideoplayer.ui.components.pressable
import com.app.xvideoplayer.ui.theme.*
import kotlinx.coroutines.launch

/**
 * The app's own number pad (no system keyboard). With a fixed [length] the code is checked as soon
 * as the last digit is typed; with null (choosing a new PIN) a ✓ key submits 4–8 digits.
 * [onEntered] returns false to reject: the dots shake and clear.
 */
@Composable
fun PinPad(
    title: String,
    subtitle: String,
    length: Int?,
    onEntered: suspend (String) -> Boolean,
    modifier: Modifier = Modifier,
    footer: @Composable () -> Unit = {}
) {
    var code by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var success by remember { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }
    val successScale = remember { Animatable(0f) }
    val successAlpha = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    fun submit() {
        if (checking || success) return
        checking = true
        scope.launch {
            val ok = onEntered(code)
            if (ok) {
                success = true
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                launch { successAlpha.animateTo(1f, tween(200)) }
                successScale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 600f))
            } else {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                for (x in listOf(18f, -16f, 12f, -8f, 4f, 0f)) shake.animateTo(x, spring(stiffness = 4000f))
                code = ""
                checking = false
            }
        }
    }

    fun press(d: Char) {
        if (checking || code.length >= (length ?: 8)) return
        code += d
        if (length != null && code.length == length) submit()
    }

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // landscape (e.g. the Thor's top screen): code on the left, keys on the right
        val wide = maxWidth > maxHeight * 1.1f
        val keySize = (if (wide) (maxHeight - 32.dp) / 4 - 10.dp else (maxHeight - 260.dp) / 4 - 14.dp).coerceIn(50.dp, 74.dp)
        val gap = if (wide) 10.dp else 14.dp
        val head: @Composable () -> Unit = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(AccentSoft).border(1.dp, Accent.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Lock, null, tint = AccentBright, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.height(14.dp))
                Text(title, style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextTertiary)
                Spacer(Modifier.height(22.dp))
                Box(Modifier.height(48.dp), contentAlignment = Alignment.Center) {
                    // Success checkmark animation (coop-style unlock)
                    if (success) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = "Unlocked",
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier
                                .size(48.dp)
                                .graphicsLayer {
                                    scaleX = successScale.value
                                    scaleY = successScale.value
                                    alpha = successAlpha.value
                                }
                        )
                    } else {
                        Row(
                            Modifier.height(20.dp).graphicsLayer { translationX = shake.value * density },
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val slots = length ?: maxOf(4, code.length)
                            repeat(slots) { i ->
                                val filled = i < code.length
                                val s by animateFloatAsState(if (filled) 1f else 0.7f, spring(dampingRatio = 0.45f, stiffness = 700f), label = "dot")
                                Box(
                                    Modifier.size(16.dp).graphicsLayer { scaleX = s; scaleY = s }.clip(CircleShape)
                                        .background(if (filled) AccentBright else SurfaceElevated3).border(1.5.dp, if (filled) AccentBright else BorderStrong, CircleShape)
                                )
                            }
                        }
                    }
                }
                footer()
            }
        }
        val keys: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                // numpad layout: 7 8 9 on top, 1 2 3 at the bottom
                listOf("789", "456", "123").forEach { r ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap + 6.dp)) { r.forEach { d -> Key(d.toString(), keySize) { press(d) } } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(gap + 6.dp)) {
                    if (length == null) Key(keySize, icon = true, enabled = code.length >= 4, onClick = { submit() }) { Icon(Icons.Rounded.Check, "OK", tint = if (code.length >= 4) AccentBright else TextTertiary, modifier = Modifier.size(28.dp)) }
                    else Spacer(Modifier.size(keySize))
                    Key("0", keySize) { press('0') }
                    Key(keySize, icon = true, enabled = code.isNotEmpty(), onClick = { if (!checking) code = code.dropLast(1) }) {
                        Icon(Icons.Rounded.Backspace, "Delete", tint = TextSecondary, modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
        if (wide) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(56.dp)) { head(); keys() }
        else Column(horizontalAlignment = Alignment.CenterHorizontally) { head(); Spacer(Modifier.height(26.dp)); keys() }
    }
}

@Composable
private fun Key(label: String, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) = Key(size, icon = false, enabled = true, onClick = onClick) {
    Text(label, fontSize = 30.sp, color = TextPrimary, style = MaterialTheme.typography.headlineMedium)
}

@Composable
private fun Key(size: androidx.compose.ui.unit.Dp, icon: Boolean, enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (icon) androidx.compose.ui.graphics.Color.Transparent else SurfaceElevated2)
            .border(1.dp, if (icon) androidx.compose.ui.graphics.Color.Transparent else Border, CircleShape)
            .then(if (enabled) Modifier.pressable(onClick, pressedScale = 0.88f) else Modifier),
        contentAlignment = Alignment.Center
    ) { content() }
}
