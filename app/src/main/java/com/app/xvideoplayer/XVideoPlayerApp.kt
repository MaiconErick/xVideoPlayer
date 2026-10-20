package com.app.xvideoplayer

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.app.xvideoplayer.ui.components.VideoThumbFetcher
import com.app.xvideoplayer.ui.components.VideoThumbKeyer
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class XVideoPlayerApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        renameLegacyStorage()
        super.onCreate()
    }

    /** One-time move of the pre-rename database and settings files, before anything opens them */
    private fun renameLegacyStorage() {
        runCatching {
            for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                val old = getDatabasePath("ultimate_player.db$suffix")
                val new = getDatabasePath("xvideoplayer.db$suffix")
                if (old.exists() && !new.exists()) old.renameTo(new)
            }
            val store = java.io.File(filesDir, "datastore")
            val old = java.io.File(store, "ultimate_player_prefs.preferences_pb")
            val new = java.io.File(store, "xvideoplayer_prefs.preferences_pb")
            if (old.exists() && !new.exists()) old.renameTo(new)
        }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            add(VideoThumbKeyer())
            add(VideoThumbFetcher.Factory(this@XVideoPlayerApp))
        }
        // no crossfade: during a fling every arriving thumbnail would animate (extra redraws)
        .crossfade(false)
        .build()
}
