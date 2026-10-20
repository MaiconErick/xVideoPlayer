package com.app.xvideoplayer

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.app.xvideoplayer.data.model.PlayerSettings
import com.app.xvideoplayer.data.preferences.AppPreferences
import com.app.xvideoplayer.navigation.AppNavHost
import com.app.xvideoplayer.ui.components.LocalResolutionLabel
import com.app.xvideoplayer.ui.theme.SurfacePure
import com.app.xvideoplayer.ui.theme.AppPalette
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.app.xvideoplayer.ui.theme.XVideoPlayerTheme
import com.app.xvideoplayer.util.PipState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var vault: com.app.xvideoplayer.data.vault.PrivateVault

    /** A video handed to us by another app (ACTION_VIEW); consumed by the nav host. */
    private val externalVideo = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        if (savedInstanceState == null) externalVideo.value = intent.videoUri()
        // read the theme before the first frame so it never flashes the default colors
        AppPalette.current = kotlinx.coroutines.runBlocking { appPreferences.themePalette.first() }
        lifecycleScope.launch { appPreferences.themePalette.collect { AppPalette.current = it } }
        setContent {
            val settings by appPreferences.playerSettings.collectAsState(initial = PlayerSettings())
            androidx.compose.runtime.LaunchedEffect(settings.gamepadJson) {
                com.app.xvideoplayer.data.model.GamepadRouter.setMapping(com.app.xvideoplayer.data.model.GamepadMap.parse(settings.gamepadJson))
            }
            XVideoPlayerTheme {
                CompositionLocalProvider(LocalResolutionLabel provides settings.resolutionLabel) {
                    Surface(modifier = Modifier.fillMaxSize(), color = SurfacePure) {
                        val navController = rememberNavController()
                        AppNavHost(
                            navController = navController,
                            externalVideo = externalVideo.value,
                            onExternalVideoConsumed = { externalVideo.value = null }
                        )
                    }
                }
            }
        }
    }

    /** Controller buttons: the player runs mapped features, the settings dialog can capture a button. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (com.app.xvideoplayer.data.model.GamepadRouter.onKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.videoUri()?.let { externalVideo.value = it }
    }

    private fun Intent.videoUri(): String? = if (action == Intent.ACTION_VIEW) data?.toString() else null

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters PiP on its own via setAutoEnterEnabled; older versions need this nudge
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && PipState.allowed) {
            runCatching {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder().setAspectRatio(Rational(PipState.aspectWidth, PipState.aspectHeight)).build()
                )
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipState.inPip.value = isInPictureInPictureMode
    }

    // the private section locks itself once the app has been in the background for a moment
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) vault.onAppBackground()
    }

    override fun onStart() {
        super.onStart()
        vault.onAppForeground()
    }
}
