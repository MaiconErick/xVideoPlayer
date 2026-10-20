package com.app.xvideoplayer.navigation

import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.app.xvideoplayer.data.model.VideoItem
import com.app.xvideoplayer.ui.cloud.CloudScreen
import com.app.xvideoplayer.ui.cloud.ServerBrowserScreen
import com.app.xvideoplayer.ui.library.FolderScreen
import com.app.xvideoplayer.ui.library.FoldersTab
import com.app.xvideoplayer.ui.library.LibraryViewModel
import com.app.xvideoplayer.ui.library.VideosTab
import com.app.xvideoplayer.ui.player.PlayerScreen
import com.app.xvideoplayer.ui.settings.SettingsScreen
import com.app.xvideoplayer.ui.settings.SubtitleStyleScreen
import com.app.xvideoplayer.ui.theme.*

object Routes {
    const val MAIN = "main"
    const val FOLDER = "folder?folder={folder}"
    const val SERVER_BROWSER = "server_browser/{serverId}"
    const val PLAYER = "player/{videoId}?uri={uri}&source={source}"
    const val SUBTITLE_STYLE = "subtitle_style"
    const val COLLAGE = "collage"
    const val TRIM = "trim/{videoId}"
    fun trim(videoId: Long) = "trim/$videoId"
    const val DUPLICATES = "duplicates"
    const val CACHED = "cached"
    const val PRIVATE = "private"
    const val PRIVATE_TAGGER = "private_tagger"

    fun folder(path: String) = "folder?folder=${Uri.encode(path)}"
    fun serverBrowser(serverId: Long) = "server_browser/$serverId"
    fun player(videoId: Long = 0, uri: String = "", source: String = "LOCAL") =
        "player/$videoId?uri=${Uri.encode(uri)}&source=$source"
}

private val enterEase = CubicBezierEasing(0.2f, 0f, 0f, 1f)

@Composable
fun AppNavHost(navController: NavHostController, externalVideo: String?, onExternalVideoConsumed: () -> Unit) {
    LaunchedEffect(externalVideo) {
        if (externalVideo != null) {
            navController.navigate(Routes.player(0, externalVideo, "EXTERNAL")) { launchSingleTop = true }
            onExternalVideoConsumed()
        }
    }
    val playVideo: (VideoItem) -> Unit = { v -> navController.navigate(Routes.player(v.id, v.uri, v.source.name)) { launchSingleTop = true } }
    val openTrim: (VideoItem) -> Unit = { v -> navController.navigate(Routes.trim(v.id)) { launchSingleTop = true } }

    androidx.compose.runtime.CompositionLocalProvider(com.app.xvideoplayer.ui.components.LocalOpenTrim provides openTrim) {

    NavHost(
        navController = navController,
        startDestination = Routes.MAIN,
        enterTransition = { fadeIn(tween(260, easing = enterEase)) + slideInHorizontally(tween(320, easing = enterEase)) { it / 6 } },
        exitTransition = { fadeOut(tween(180)) + slideOutHorizontally(tween(320, easing = enterEase)) { -it / 12 } },
        popEnterTransition = { fadeIn(tween(260, easing = enterEase)) + slideInHorizontally(tween(320, easing = enterEase)) { -it / 12 } },
        popExitTransition = { fadeOut(tween(180)) + slideOutHorizontally(tween(320, easing = enterEase)) { it / 6 } }
    ) {
        composable(Routes.MAIN) {
            MainScreen(
                onPlay = playVideo,
                onOpenFolder = { navController.navigate(Routes.folder(it)) },
                onOpenCached = { navController.navigate(Routes.CACHED) { launchSingleTop = true } },
                onBrowseServer = { navController.navigate(Routes.serverBrowser(it)) },
                onPlayUrl = { navController.navigate(Routes.player(0, it, "HTTP")) },
                onSubtitleStyle = { navController.navigate(Routes.SUBTITLE_STYLE) },
                onOpenDuplicates = { navController.navigate(Routes.DUPLICATES) },
                onOpenPrivate = { navController.navigate(Routes.PRIVATE) { launchSingleTop = true } }
            )
        }

        composable(Routes.PRIVATE) {
            com.app.xvideoplayer.ui.vault.PrivateScreen(
                onBack = { navController.popBackStack() },
                onPlay = playVideo,
                onOpenTagger = { navController.navigate(Routes.PRIVATE_TAGGER) { launchSingleTop = true } }
            )
        }

        composable(Routes.PRIVATE_TAGGER) {
            com.app.xvideoplayer.ui.vault.TaggerScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.FOLDER, arguments = listOf(navArgument("folder") { type = NavType.StringType; defaultValue = "" })) {
            FolderScreen(onBack = { navController.popBackStack() }, onPlay = playVideo, onOpenFolder = { navController.navigate(Routes.folder(it)) })
        }

        composable(Routes.CACHED) {
            com.app.xvideoplayer.ui.cloud.CachedScreen(onBack = { navController.popBackStack() }, onPlay = { uri -> navController.navigate(Routes.player(0, uri, "SMB")) })
        }

        composable(Routes.SERVER_BROWSER, arguments = listOf(navArgument("serverId") { type = NavType.LongType })) { entry ->
            ServerBrowserScreen(
                serverId = entry.arguments?.getLong("serverId") ?: 0L,
                onBack = { navController.popBackStack() },
                onPlayVideo = { id, uri, source -> navController.navigate(Routes.player(id, uri, source)) }
            )
        }

        composable(
            Routes.PLAYER,
            arguments = listOf(
                navArgument("videoId") { type = NavType.LongType },
                navArgument("uri") { type = NavType.StringType; defaultValue = "" },
                navArgument("source") { type = NavType.StringType; defaultValue = "LOCAL" }
            ),
            // the screen usually rotates on entry/exit, so a plain fade reads best
            enterTransition = { fadeIn(tween(220)) },
            exitTransition = { fadeOut(tween(160)) },
            popEnterTransition = { fadeIn(tween(220)) },
            popExitTransition = { fadeOut(tween(160)) }
        ) { entry ->
            PlayerScreen(
                videoId = entry.arguments?.getLong("videoId") ?: 0L,
                videoUri = entry.arguments?.getString("uri").orEmpty(),
                source = entry.arguments?.getString("source") ?: "LOCAL",
                onBack = { navController.popBackStack() },
                onNavigateToSubtitleStyle = { navController.navigate(Routes.SUBTITLE_STYLE) },
                onOpenCollage = { navController.navigate(Routes.COLLAGE) { launchSingleTop = true } }
            )
        }

        composable(Routes.TRIM, arguments = listOf(navArgument("videoId") { type = NavType.LongType })) {
            com.app.xvideoplayer.ui.trim.TrimScreen(
                onBack = { navController.popBackStack() },
                onPlay = { uri -> navController.navigate(Routes.player(0, uri.toString(), "EXTERNAL")) }
            )
        }

        composable(Routes.COLLAGE) {
            com.app.xvideoplayer.ui.collage.CollageScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.DUPLICATES) {
            com.app.xvideoplayer.ui.tools.DuplicatesScreen(onBack = { navController.popBackStack() }, onPlay = playVideo)
        }

        composable(Routes.SUBTITLE_STYLE) {
            SubtitleStyleScreen(onBack = { navController.popBackStack() })
        }
    }
    }
}

// ── Main screen with the four tabs ──────────────────────────────────────

private enum class Tab(val label: String, val icon: ImageVector, val iconSelected: ImageVector) {
    HOME("Home", Icons.Outlined.Home, Icons.Rounded.Home),
    VIDEOS("Videos", Icons.Outlined.VideoLibrary, Icons.Rounded.VideoLibrary),
    FOLDERS("Folders", Icons.Outlined.Folder, Icons.Rounded.Folder),
    NETWORK("Network", Icons.Outlined.Cloud, Icons.Rounded.Cloud),
    SETTINGS("Settings", Icons.Outlined.Settings, Icons.Rounded.Settings)
}

@Composable
private fun MainScreen(
    onPlay: (VideoItem) -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenCached: () -> Unit,
    onBrowseServer: (Long) -> Unit,
    onPlayUrl: (String) -> Unit,
    onSubtitleStyle: () -> Unit,
    onOpenDuplicates: () -> Unit,
    onOpenPrivate: () -> Unit
) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    // separate instances so each tab keeps its own search box
    val videosVm: LibraryViewModel = hiltViewModel(key = "videos")
    val foldersVm: LibraryViewModel = hiltViewModel(key = "folders")

    Box(Modifier.fillMaxSize().background(SurfacePure)) {
    if (com.app.xvideoplayer.ui.theme.AppPalette.current.halloween) com.app.xvideoplayer.ui.theme.halloween.HalloweenBackdrop()
    Column(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (fadeIn(tween(220, delayMillis = 40)) + slideInHorizontally(tween(300, easing = enterEase)) { dir * it / 14 }) togetherWith
                    (fadeOut(tween(140)) + slideOutHorizontally(tween(300, easing = enterEase)) { -dir * it / 20 })
            },
            modifier = Modifier.weight(1f),
            label = "tabs"
        ) { current ->
            when (current) {
                Tab.HOME -> com.app.xvideoplayer.ui.home.HomeScreen(
                    onOpenDuplicates = onOpenDuplicates,
                    onOpenPrivate = onOpenPrivate,
                    onOpenNetwork = { tab = Tab.NETWORK },
                    onOpenVideos = { tab = Tab.VIDEOS }
                )
                Tab.VIDEOS -> VideosTab(onPlay = onPlay, viewModel = videosVm)
                Tab.FOLDERS -> FoldersTab(onOpenFolder = onOpenFolder, onOpenCached = onOpenCached, viewModel = foldersVm)
                Tab.NETWORK -> CloudScreen(onBrowseServer = onBrowseServer, onPlayUrl = onPlayUrl)
                Tab.SETTINGS -> SettingsScreen(onNavigateToSubtitleStyle = onSubtitleStyle)
            }
        }
        // overall offline-copy progress, visible from every tab; tap = Network tab (the per-file list)
        com.app.xvideoplayer.ui.cloud.OverallDownloadBar(onClick = { tab = Tab.NETWORK })
        BottomBar(tab) { tab = it }
    }
    }
}

@Composable
private fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(SurfaceElevated1)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(68.dp).padding(horizontal = 8.dp)) {
            Tab.entries.forEach { t ->
                val isSel = t == selected
                val pillWidth by animateDpAsState(if (isSel) 60.dp else 28.dp, spring(dampingRatio = 0.65f, stiffness = 500f), label = "pill")
                val pillAlpha by animateFloatAsState(if (isSel) 1f else 0f, tween(200), label = "pillA")
                val tint by animateColorAsState(if (isSel) AccentBright else TextTertiary, tween(200), label = "tint")
                val iconScale by animateFloatAsState(if (isSel) 1f else 0.92f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "scale")
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .selectable(selected = isSel, role = Role.Tab, interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(t) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isSel && com.app.xvideoplayer.ui.theme.AppPalette.current.halloween) com.app.xvideoplayer.ui.theme.halloween.CandleGlow(Modifier.matchParentSize().graphicsLayer { scaleX = 1.6f; scaleY = 2.4f })
                        Box(Modifier.width(pillWidth).height(30.dp).clip(CircleShape).background(AccentSoft.copy(alpha = AccentSoft.alpha * pillAlpha)))
                        Icon(if (isSel) t.iconSelected else t.icon, t.label, tint = tint, modifier = Modifier.size((24 * iconScale).dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(t.label, color = tint, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
