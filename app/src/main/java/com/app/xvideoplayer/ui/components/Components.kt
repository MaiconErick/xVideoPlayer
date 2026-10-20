package com.app.xvideoplayer.ui.components

import android.os.Build
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.app.xvideoplayer.data.model.*
import com.app.xvideoplayer.ui.theme.*
import com.app.xvideoplayer.util.formatDuration
import com.app.xvideoplayer.util.formatFileSize
import com.app.xvideoplayer.util.formatRelativeTime

// ── Motion helpers ──────────────────────────────────────────────────────

/** Click + long-click with a soft press-down scale, used by every tappable card. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.pressable(onClick: () -> Unit, onLongClick: (() -> Unit)? = null, pressedScale: Float = 0.965f): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, spring(dampingRatio = 0.6f, stiffness = 600f), label = "press")
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .combinedClickable(interactionSource = source, indication = null, onClick = onClick, onLongClick = onLongClick)
}

// ── Thumbnails ──────────────────────────────────────────────────────────

/**
 * Landscape videos fill the frame; portrait ones are shown whole over a blurred,
 * dimmed copy of themselves so the 16:9 slot never looks empty.
 */
@Composable
fun VideoThumbnail(video: VideoItem, modifier: Modifier = Modifier) {
    val model = remember(video.uri) { if (video.source == VideoSource.LOCAL) VideoThumb(video.uri, video.path) else null }
    Box(modifier.background(SurfaceElevated3)) {
        if (model == null) {
            Icon(Icons.Rounded.Movie, null, tint = TextTertiary, modifier = Modifier.align(Alignment.Center).size(28.dp))
        } else if (video.isPortrait) {
            // tiny 24px copy stretched up = soft blurred backdrop, no per-frame RenderEffect
            val backdrop = remember(video.uri) { VideoThumb(video.uri, video.path, backdrop = true) }
            AsyncImage(
                model = backdrop, contentDescription = null, contentScale = ContentScale.Crop, alpha = 0.45f,
                modifier = Modifier.fillMaxSize()
            )
            AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun ThumbBadges(video: VideoItem, compact: Boolean) {
    Box(Modifier.fillMaxSize()) {
        if (video.duration > 0) {
            Text(
                video.duration.formatDuration(),
                color = Color.White, fontSize = if (compact) 10.sp else 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp)
                    .background(Color(0xB3000000), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp)
            )
        }
        Row(Modifier.align(Alignment.TopStart).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (video.isFavorite) MiniBadge(Icons.Rounded.Favorite, ErrorRed)
            if (video.playedStatus == PlayedStatus.WATCHED) MiniBadge(Icons.Rounded.Check, PlayedGreen)
        }
        if (video.playedStatus == PlayedStatus.IN_PROGRESS && video.duration > 0) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x66FFFFFF))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth((video.progressPercent / 100f).coerceIn(0.02f, 1f)).background(Accent))
            }
        }
    }
}

@Composable
private fun MiniBadge(icon: ImageVector, tint: Color) {
    Box(Modifier.size(18.dp).background(Color(0xB3000000), CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(12.dp))
    }
}

/** Resolution label style chosen in Settings; provided once at the app root. */
val LocalResolutionLabel = staticCompositionLocalOf { ResolutionLabel.SHORT }

fun VideoItem.qualityLabel(style: ResolutionLabel = ResolutionLabel.SHORT): String? {
    if (!hasDimensions) return null
    val short = minOf(width, height)
    val p = when {
        short >= 2100 -> "4K"
        short >= 1400 -> "1440p"
        short >= 1000 -> "1080p"
        short >= 700 -> "720p"
        else -> "${short}p"
    }
    val full = "$width×$height"
    return when (style) {
        ResolutionLabel.SHORT -> p
        ResolutionLabel.FULL -> full
        ResolutionLabel.BOTH -> "$p ($full)"
    }
}

/** Built once per video/label style instead of re-running the formatters on every recomposition */
@Composable
private fun rememberMetaLine(video: VideoItem): String {
    val style = LocalResolutionLabel.current
    return remember(video, style) { video.metaLine(style) }
}

private fun VideoItem.metaLine(style: ResolutionLabel): String = listOfNotNull(
    qualityLabel(style),
    size.takeIf { it > 0 }?.formatFileSize(),
    lastPlayed?.formatRelativeTime()
).joinToString("  ·  ")

// ── Video row (list mode) ───────────────────────────────────────────────

@Composable
fun VideoRow(
    video: VideoItem, onClick: () -> Unit, onMore: () -> Unit, modifier: Modifier = Modifier, thumbWidth: Dp = 156.dp,
    selected: Boolean = false, onLongClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth().background(if (selected) AccentSoft else Color.Transparent)
            .pressable(onClick, onLongClick ?: onMore).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(thumbWidth).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) {
            VideoThumbnail(video, Modifier.fillMaxSize())
            ThumbBadges(video, compact = true)
            if (selected) SelectedMark()
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(video.title, style = MaterialTheme.typography.titleSmall, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (video.isPortrait) {
                    Icon(Icons.Rounded.StayCurrentPortrait, null, tint = TextTertiary, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(3.dp))
                }
                Text(rememberMetaLine(video), style = MaterialTheme.typography.bodySmall, color = TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onMore, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.MoreVert, "More", tint = TextTertiary, modifier = Modifier.size(20.dp))
        }
    }
}

// ── Video card (grid mode) ──────────────────────────────────────────────

@Composable
fun VideoCard(video: VideoItem, onClick: () -> Unit, onMore: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, onLongClick: (() -> Unit)? = null) {
    Column(modifier.pressable(onClick, onLongClick ?: onMore)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp))) {
            VideoThumbnail(video, Modifier.fillMaxSize())
            ThumbBadges(video, compact = false)
            if (selected) SelectedMark()
        }
        Spacer(Modifier.height(8.dp))
        Text(video.title, style = MaterialTheme.typography.titleSmall, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
        Text(rememberMetaLine(video), style = MaterialTheme.typography.bodySmall, color = TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp))
    }
}

/** Dim + check mark drawn over a selected thumbnail. */
@Composable
private fun BoxScope.SelectedMark() {
    Box(Modifier.matchParentSize().background(Accent.copy(alpha = 0.35f)))
    Box(Modifier.align(Alignment.Center).size(34.dp).background(Accent, CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Check, null, tint = OnAccent, modifier = Modifier.size(22.dp))
    }
}

/** Square-ish folder tile for the grid layout of the folder lists. */
@Composable
fun FolderCard(folder: FolderItem, cover: VideoItem?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.pressable(onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(14.dp)).border(1.dp, Border, RoundedCornerShape(14.dp))) {
            if (cover != null) VideoThumbnail(cover, Modifier.fillMaxSize()) else Box(Modifier.fillMaxSize().background(SurfaceElevated3))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color(0xB3000000))))
            Icon(Icons.Rounded.Folder, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).size(20.dp))
            Text("${folder.videoCount}", color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp))
            if (folder.hasInProgress) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(8.dp).background(Accent, CircleShape))
        }
        Spacer(Modifier.height(6.dp))
        Text(folder.name, style = MaterialTheme.typography.titleSmall, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
        Text(folder.totalSize.formatFileSize(), style = MaterialTheme.typography.bodySmall, color = TextTertiary, modifier = Modifier.padding(horizontal = 2.dp))
    }
}

// ── Continue-watching card ──────────────────────────────────────────────

@Composable
fun ContinueCard(video: VideoItem, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.width(264.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp)).pressable(onClick, onLongClick)) {
        VideoThumbnail(video, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xE6000000))))
        Box(Modifier.align(Alignment.Center).size(46.dp).background(Color(0x80000000), CircleShape).border(1.dp, Color(0x40FFFFFF), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(video.title, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val left = (video.duration - video.lastPosition).coerceAtLeast(0)
            Text("${left.formatDuration()} left", color = Color(0xCCFFFFFF), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color(0x40FFFFFF))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth((video.progressPercent / 100f).coerceIn(0.02f, 1f)).clip(CircleShape).background(Accent))
            }
        }
    }
}

// ── Folder row ──────────────────────────────────────────────────────────

@Composable
fun FolderRow(folder: FolderItem, cover: VideoItem?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().pressable(onClick).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(112.dp).aspectRatio(16f / 10f)) {
            // stacked-card look: a sliver of a second card peeking behind the cover
            Box(Modifier.matchParentSize().padding(horizontal = 8.dp).offset(y = (-5).dp).clip(RoundedCornerShape(12.dp)).background(SurfaceElevated4))
            Box(Modifier.matchParentSize().clip(RoundedCornerShape(12.dp)).border(1.dp, Border, RoundedCornerShape(12.dp))) {
                if (cover != null) VideoThumbnail(cover, Modifier.fillMaxSize())
                else Box(Modifier.fillMaxSize().background(SurfaceElevated3))
                Box(Modifier.fillMaxSize().background(Color(0x33000000)))
                Icon(Icons.Rounded.Folder, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).size(18.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(folder.name, style = MaterialTheme.typography.titleSmall, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(
                "${folder.videoCount} video${if (folder.videoCount == 1) "" else "s"}  ·  ${folder.totalSize.formatFileSize()}",
                style = MaterialTheme.typography.bodySmall, color = TextTertiary
            )
        }
        if (folder.hasInProgress) Box(Modifier.size(7.dp).background(Accent, CircleShape))
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Rounded.ChevronRight, null, tint = TextTertiary)
    }
}

// ── Header / chips ──────────────────────────────────────────────────────

/** Big-title header with an expanding search field. */
@Composable
fun LibraryHeader(
    title: String,
    subtitle: String?,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    var searching by rememberSaveable { mutableStateOf(searchQuery.isNotEmpty()) }
    val focus = LocalFocusManager.current
    Column(modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp)) {
        AnimatedContent(
            targetState = searching,
            transitionSpec = { (fadeIn(tween(220)) + slideInVertically { -it / 3 }) togetherWith (fadeOut(tween(150))) },
            label = "header"
        ) { isSearching ->
            if (isSearching) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = searchQuery, onValueChange = onSearchChange, singleLine = true,
                        placeholder = { Text("Search videos", color = TextTertiary) },
                        leadingIcon = { Icon(Icons.Rounded.Search, null, tint = TextSecondary) },
                        trailingIcon = {
                            IconButton(onClick = { onSearchChange(""); searching = false; focus.clearFocus() }) {
                                Icon(Icons.Rounded.Close, "Close search", tint = TextSecondary)
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        shape = RoundedCornerShape(16.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = SurfaceElevated2, unfocusedContainerColor = SurfaceElevated2,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = Accent, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(start = if (onBack != null) 4.dp else 20.dp, end = 8.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back", tint = TextPrimary) }
                        Spacer(Modifier.width(4.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(title, style = if (onBack != null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineLarge, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (com.app.xvideoplayer.ui.theme.AppPalette.current.halloween && onBack == null) { Spacer(Modifier.width(10.dp)); com.app.xvideoplayer.ui.theme.halloween.FlappingBat(18.dp) }
                        }
                        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { searching = true }) { Icon(Icons.Rounded.Search, "Search", tint = TextPrimary) }
                    actions()
                }
            }
        }
    }
}

@Composable
fun AppChip(
    label: String,
    selected: Boolean = false,
    icon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    onClick: () -> Unit
) {
    val bg by animateColorAsState(if (selected) AccentSoft else SurfaceElevated2, tween(200), label = "chipBg")
    val fg by animateColorAsState(if (selected) AccentBright else TextSecondary, tween(200), label = "chipFg")
    val border by animateColorAsState(if (selected) Accent.copy(alpha = 0.55f) else Border, tween(200), label = "chipBorder")
    Row(
        Modifier.height(34.dp).clip(CircleShape).background(bg).border(1.dp, border, CircleShape)
            .pressable(onClick, pressedScale = 0.94f).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) { Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
        Text(label, color = fg, style = MaterialTheme.typography.labelMedium)
        if (trailingIcon != null) { Spacer(Modifier.width(2.dp)); Icon(trailingIcon, null, tint = fg, modifier = Modifier.size(18.dp)) }
    }
}

/** The "All / Landscape / Portrait" dropdown. */
@Composable
fun OrientationFilterChip(value: OrientationFilter, onChange: (OrientationFilter) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AppChip(
            label = when (value) { OrientationFilter.ALL -> "All shapes"; OrientationFilter.LANDSCAPE -> "Landscape"; OrientationFilter.PORTRAIT -> "Portrait" },
            selected = value != OrientationFilter.ALL,
            icon = orientationIcon(value),
            trailingIcon = Icons.Rounded.ArrowDropDown,
            onClick = { open = true }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = RoundedCornerShape(16.dp), containerColor = SurfaceElevated3) {
            OrientationFilter.entries.forEach { f ->
                DropdownMenuItem(
                    text = { Text(when (f) { OrientationFilter.ALL -> "All videos"; OrientationFilter.LANDSCAPE -> "Landscape only"; OrientationFilter.PORTRAIT -> "Portrait only" }) },
                    leadingIcon = { Icon(orientationIcon(f), null, tint = if (f == value) Accent else TextSecondary) },
                    trailingIcon = { if (f == value) Icon(Icons.Rounded.Check, null, tint = Accent) },
                    onClick = { onChange(f); open = false }
                )
            }
        }
    }
}

/** Same filter as the chip, but a compact header icon (next to search and refresh). */
@Composable
fun OrientationIconButton(value: OrientationFilter, onChange: (OrientationFilter) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(orientationIcon(value), "Video shape", tint = if (value != OrientationFilter.ALL) Accent else TextPrimary) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = RoundedCornerShape(16.dp), containerColor = SurfaceElevated3) {
            OrientationFilter.entries.forEach { f ->
                DropdownMenuItem(
                    text = { Text(when (f) { OrientationFilter.ALL -> "All shapes"; OrientationFilter.LANDSCAPE -> "Landscape only"; OrientationFilter.PORTRAIT -> "Portrait only" }) },
                    leadingIcon = { Icon(orientationIcon(f), null, tint = if (f == value) Accent else TextSecondary) },
                    trailingIcon = { if (f == value) Icon(Icons.Rounded.Check, null, tint = Accent) },
                    onClick = { onChange(f); open = false }
                )
            }
        }
    }
}

private fun orientationIcon(f: OrientationFilter) = when (f) {
    OrientationFilter.ALL -> Icons.Rounded.Crop
    OrientationFilter.LANDSCAPE -> Icons.Rounded.StayCurrentLandscape
    OrientationFilter.PORTRAIT -> Icons.Rounded.StayCurrentPortrait
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, startPadding: Dp = 20.dp, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = startPadding, end = 12.dp, top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = TextPrimary, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

// ── Empty / scanning states ─────────────────────────────────────────────

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null
) {
    val float = rememberInfiniteTransition(label = "float")
    val dy by float.animateFloat(0f, -8f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "dy")
    Column(
        modifier.fillMaxSize().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        if (com.app.xvideoplayer.ui.theme.AppPalette.current.halloween) com.app.xvideoplayer.ui.theme.halloween.WanderingGhost(80.dp)
        else Box(Modifier.offset(y = dy.dp).size(88.dp).background(SurfaceElevated2, CircleShape).border(1.dp, Border, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = TextSecondary, modifier = Modifier.size(38.dp))
        }
        Spacer(Modifier.height(22.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = TextPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, textAlign = TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(20.dp)); action() }
    }
}

/** Only shown on the very first launch, while the library is still empty. */
@Composable
fun FirstScanState(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "scan")
    val ring by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "ring")
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (com.app.xvideoplayer.ui.theme.AppPalette.current.halloween) com.app.xvideoplayer.ui.theme.halloween.CauldronLoader(96.dp)
        else Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
            listOf(0f, 0.5f).forEach { phase ->
                val p = (ring + phase) % 1f
                Box(Modifier.size(140.dp).scale(0.45f + p * 0.55f).graphicsLayer { alpha = 1f - p }.border(2.dp, Accent, CircleShape))
            }
            Box(Modifier.size(64.dp).background(AccentSoft, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.VideoLibrary, null, tint = Accent, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("Building your library", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        Text("This only happens once", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
    }
}

/** Opens the lossless cut editor for a video; provided by the nav host. */
val LocalOpenTrim = staticCompositionLocalOf<(VideoItem) -> Unit> { {} }

// ── Action sheet for a video ────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoActionsSheet(
    video: VideoItem,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onMarkWatched: () -> Unit,
    onMarkUnwatched: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onInfo: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SurfaceElevated2, dragHandle = { SheetHandle() }) {
        Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) { VideoThumbnail(video, Modifier.fillMaxSize()) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(video.title, style = MaterialTheme.typography.titleSmall, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(video.folderName, style = MaterialTheme.typography.bodySmall, color = TextTertiary, maxLines = 1)
            }
        }
        HorizontalDivider(color = Border)
        Column(Modifier.padding(vertical = 6.dp)) {
            SheetAction(Icons.Rounded.PlayArrow, if (video.playedStatus == PlayedStatus.IN_PROGRESS) "Resume" else "Play") { onDismiss(); onPlay() }
            SheetAction(if (video.isFavorite) Icons.Rounded.HeartBroken else Icons.Rounded.FavoriteBorder, if (video.isFavorite) "Remove from favorites" else "Add to favorites") { onDismiss(); onToggleFavorite() }
            if (video.playedStatus != PlayedStatus.WATCHED) SheetAction(Icons.Rounded.CheckCircleOutline, "Mark as watched") { onDismiss(); onMarkWatched() }
            if (video.playedStatus != PlayedStatus.UNWATCHED) SheetAction(Icons.Rounded.RadioButtonUnchecked, "Mark as unwatched") { onDismiss(); onMarkUnwatched() }
            SheetAction(Icons.Rounded.Edit, "Rename") { onDismiss(); onRename() }
            if (video.source == VideoSource.LOCAL) {
                val openTrim = LocalOpenTrim.current
                SheetAction(Icons.Rounded.ContentCut, "Cut (lossless)") { onDismiss(); openTrim(video) }
            }
            SheetAction(Icons.Rounded.Info, "Details") { onDismiss(); onInfo() }
            SheetAction(Icons.Rounded.DeleteOutline, "Delete", tint = ErrorRed) { onDismiss(); onDelete() }
        }
        Spacer(Modifier.navigationBarsPadding().height(8.dp))
    }
}

@Composable
fun SheetHandle() {
    Box(Modifier.padding(top = 10.dp, bottom = 14.dp).size(width = 36.dp, height = 4.dp).background(BorderStrong, CircleShape))
}

@Composable
fun SheetAction(icon: ImageVector, label: String, tint: Color = TextPrimary, trailing: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (tint == TextPrimary) TextSecondary else tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.bodyMedium, color = Accent)
    }
}

// ── Dialogs ─────────────────────────────────────────────────────────────

@Composable
fun RenameDialog(currentName: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = { AppTextField(name, { name = it }, "Name") },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text("Rename", color = Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) } },
        containerColor = SurfaceElevated2, shape = RoundedCornerShape(24.dp)
    )
}

@Composable
fun DeleteDialog(videoTitle: String, onConfirm: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var alsoDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove video?") },
        text = {
            Column {
                Text("\"$videoTitle\" will be removed from the library.", color = TextSecondary)
                Spacer(Modifier.height(14.dp))
                Row(Modifier.clip(RoundedCornerShape(12.dp)).clickable { alsoDelete = !alsoDelete }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = alsoDelete, onCheckedChange = { alsoDelete = it }, colors = CheckboxDefaults.colors(checkedColor = ErrorRed))
                    Text("Also delete the file from the device", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(alsoDelete) }) { Text(if (alsoDelete) "Delete file" else "Remove", color = ErrorRed) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) } },
        containerColor = SurfaceElevated2, shape = RoundedCornerShape(24.dp)
    )
}

@Composable
fun VideoInfoDialog(video: VideoItem, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoRow("Title", video.title)
                InfoRow("Length", video.duration.formatDuration())
                InfoRow("Size", video.size.formatFileSize())
                InfoRow("Resolution", if (video.hasDimensions) "${video.width} × ${video.height}" else "Unknown")
                InfoRow("Folder", video.folderName)
                InfoRow("Path", video.path)
                InfoRow("Played", "${video.playCount}×")
                InfoRow("Added", video.dateAdded.formatRelativeTime())
                video.lastPlayed?.let { InfoRow("Last played", it.formatRelativeTime()) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = Accent) } },
        containerColor = SurfaceElevated2, shape = RoundedCornerShape(24.dp)
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = TextTertiary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(92.dp))
        Text(value, color = TextPrimary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
fun AppTextField(
    value: String, onValueChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, placeholder: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, singleLine = true,
        label = { Text(label) }, placeholder = placeholder?.let { { Text(it, color = TextTertiary) } },
        keyboardOptions = keyboardOptions, visualTransformation = visualTransformation,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent, unfocusedBorderColor = BorderStrong, cursorColor = Accent,
            focusedLabelColor = Accent, unfocusedLabelColor = TextSecondary,
            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary
        ),
        modifier = modifier.fillMaxWidth()
    )
}

// ── Sort / filter sheet ─────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SortFilterSheet(
    sortConfig: SortConfig,
    filterConfig: FilterConfig,
    onSortChanged: (SortConfig) -> Unit,
    onFilterChanged: (FilterConfig) -> Unit,
    onDismiss: () -> Unit,
    showFilter: Boolean = true
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SurfaceElevated2, dragHandle = { SheetHandle() }) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("Sort by", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SortField.entries.filter { it != SortField.FOLDER }.forEach { f ->
                    AppChip(
                        label = when (f) {
                            SortField.TITLE -> "Name"; SortField.DATE_ADDED -> "Date added"; SortField.LAST_PLAYED -> "Last played"
                            SortField.DURATION -> "Length"; SortField.SIZE -> "Size"; SortField.PLAY_COUNT -> "Most played"; SortField.FOLDER -> "Folder"
                        },
                        selected = sortConfig.field == f
                    ) { onSortChanged(sortConfig.copy(field = f)) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppChip("Ascending", sortConfig.order == SortOrder.ASCENDING, Icons.Rounded.ArrowUpward) { onSortChanged(sortConfig.copy(order = SortOrder.ASCENDING)) }
                AppChip("Descending", sortConfig.order == SortOrder.DESCENDING, Icons.Rounded.ArrowDownward) { onSortChanged(sortConfig.copy(order = SortOrder.DESCENDING)) }
            }
            if (showFilter) {
            Spacer(Modifier.height(24.dp))
            Text("Show", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppChip("Everything", filterConfig.status == null && !filterConfig.favoritesOnly) { onFilterChanged(filterConfig.copy(status = null, favoritesOnly = false)) }
                AppChip("Unwatched", filterConfig.status == PlayedStatus.UNWATCHED) { onFilterChanged(filterConfig.copy(status = if (filterConfig.status == PlayedStatus.UNWATCHED) null else PlayedStatus.UNWATCHED)) }
                AppChip("In progress", filterConfig.status == PlayedStatus.IN_PROGRESS) { onFilterChanged(filterConfig.copy(status = if (filterConfig.status == PlayedStatus.IN_PROGRESS) null else PlayedStatus.IN_PROGRESS)) }
                AppChip("Watched", filterConfig.status == PlayedStatus.WATCHED) { onFilterChanged(filterConfig.copy(status = if (filterConfig.status == PlayedStatus.WATCHED) null else PlayedStatus.WATCHED)) }
                AppChip("Favorites", filterConfig.favoritesOnly, Icons.Rounded.Favorite) { onFilterChanged(filterConfig.copy(favoritesOnly = !filterConfig.favoritesOnly)) }
            }
            }
            Spacer(Modifier.navigationBarsPadding().height(28.dp))
        }
    }
}
