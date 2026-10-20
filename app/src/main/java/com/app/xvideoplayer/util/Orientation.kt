package com.app.xvideoplayer.util

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.OrientationEventListener
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.ref.WeakReference

/**
 * Leaving the player used to drop the orientation lock straight back to "unspecified", so a
 * landscape video snapped the app back to portrait while the phone was still held sideways.
 *
 * Instead the app keeps whatever orientation it is showing and only hands control back to
 * the system once the device is physically turned to the other orientation.
 */
object OrientationHandoff {
    private var listener: OrientationEventListener? = null

    fun cancel() {
        listener?.disable()
        listener = null
    }

    /** With auto-rotate off the system already has the right orientation (portrait-only phone, landscape-only Thor). */
    private fun autoRotateOn(activity: Activity) =
        android.provider.Settings.System.getInt(activity.contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, 1) == 1

    /** Call just before leaving the player so the screen behind it never shows up in the wrong orientation. */
    fun prepareExit(activity: Activity) {
        if (!autoRotateOn(activity)) { cancel(); activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    fun release(activity: Activity) {
        cancel()
        if (activity.isFinishing) return
        if (!autoRotateOn(activity)) { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED; return }
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        val ref = WeakReference(activity)
        val l = object : OrientationEventListener(activity) {
            override fun onOrientationChanged(degrees: Int) {
                val act = ref.get()
                if (act == null || act.isFinishing) { cancel(); return }
                if (degrees == ORIENTATION_UNKNOWN) return
                val nearUpright = degrees <= 25 || degrees >= 335 || degrees in 155..205
                val nearSideways = degrees in 65..115 || degrees in 245..295
                if (!nearUpright && !nearSideways) return
                val holdingPortrait = if (naturallyPortrait(act)) nearUpright else nearSideways
                val showingPortrait = act.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                if (holdingPortrait != showingPortrait) {
                    cancel()
                    act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }
        if (l.canDetectOrientation()) { listener = l; l.enable() }
        else activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    /** Phones/Thor are portrait at rotation 0; most tablets are landscape. */
    private fun naturallyPortrait(activity: Activity): Boolean {
        @Suppress("DEPRECATION")
        val rotation = activity.windowManager.defaultDisplay.rotation
        val portraitNow = activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val rotated = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
        return portraitNow != rotated
    }
}

/** Shared between MainActivity (which owns PiP) and the player screen (which knows if it makes sense). */
object PipState {
    /** Set by the player while a video is open and PiP is enabled in settings */
    @Volatile var allowed: Boolean = false
    @Volatile var aspectWidth: Int = 16
    @Volatile var aspectHeight: Int = 9
    val inPip = MutableStateFlow(false)
}
