package com.app.xvideoplayer.ui.components

import android.app.Activity
import android.content.IntentSender
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.app.xvideoplayer.data.FileOpResult
import com.app.xvideoplayer.data.FileOps
import com.app.xvideoplayer.data.FileOpsEntryPoint
import com.app.xvideoplayer.data.model.VideoItem
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Delete / rename real files from any screen, showing Android's confirmation dialog when required. */
class FileActions internal constructor(
    private val ops: FileOps,
    private val scope: CoroutineScope,
    private val confirm: (IntentSender, suspend (Boolean) -> Unit) -> Unit,
    private val toast: (String) -> Unit
) {
    fun delete(videos: List<VideoItem>, onDone: () -> Unit = {}) {
        if (videos.isEmpty()) return
        scope.launch {
            val label = if (videos.size == 1) "\"${videos[0].title}\"" else "${videos.size} videos"
            when (val r = ops.delete(videos)) {
                FileOpResult.Done -> { toast("Deleted $label"); onDone() }
                is FileOpResult.NeedsConfirmation -> confirm(r.intentSender) { ok ->
                    if (ok) { ops.finishDelete(videos); toast("Deleted $label"); onDone() }
                }
                is FileOpResult.Failed -> toast(r.message)
            }
        }
    }

    /** Overwrites [target]'s file with [source] (a cut), asking Android for write access if needed. */
    fun replace(target: VideoItem, source: android.net.Uri, durationMs: Long, onDone: () -> Unit = {}) {
        scope.launch {
            when (val r = ops.overwrite(target, source, durationMs)) {
                FileOpResult.Done -> { toast("Original replaced"); onDone() }
                is FileOpResult.NeedsConfirmation -> confirm(r.intentSender) { ok ->
                    if (ok) when (val again = ops.overwrite(target, source, durationMs)) {
                        FileOpResult.Done -> { toast("Original replaced"); onDone() }
                        is FileOpResult.Failed -> toast(again.message)
                        else -> toast("Not allowed to modify the original")
                    }
                }
                is FileOpResult.Failed -> toast(r.message)
            }
        }
    }

    fun rename(video: VideoItem, newTitle: String, onDone: () -> Unit = {}) {
        scope.launch {
            when (val r = ops.rename(video, newTitle)) {
                FileOpResult.Done -> { toast("Renamed"); onDone() }
                is FileOpResult.NeedsConfirmation -> confirm(r.intentSender) { ok ->
                    if (ok) when (val again = ops.rename(video, newTitle)) {
                        FileOpResult.Done -> { toast("Renamed"); onDone() }
                        is FileOpResult.Failed -> toast(again.message)
                        else -> toast("Rename not allowed")
                    }
                }
                is FileOpResult.Failed -> toast(r.message)
            }
        }
    }
}

@Composable
fun rememberFileActions(): FileActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pending = remember { arrayOfNulls<suspend (Boolean) -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val cb = pending[0]; pending[0] = null
        if (cb != null) scope.launch { cb(res.resultCode == Activity.RESULT_OK) }
    }
    return remember {
        FileActions(
            ops = EntryPointAccessors.fromApplication(context.applicationContext, FileOpsEntryPoint::class.java).fileOps(),
            scope = scope,
            confirm = { sender, cb -> pending[0] = cb; launcher.launch(IntentSenderRequest.Builder(sender).build()) },
            toast = { Toast.makeText(context.applicationContext, it, Toast.LENGTH_SHORT).show() }
        )
    }
}
