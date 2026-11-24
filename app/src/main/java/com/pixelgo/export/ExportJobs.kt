package com.pixelgo.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.pixelgo.data.video.ExportCancelled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** A finished (or running) export, observed by the editor, gallery and notification. */
data class ExportState(
    val projectId: String,
    val title: String,
    val progress: Float = 0f,
    val done: Boolean = false,
    val error: String? = null,
    val resultPath: String? = null,
    val resultUri: Uri? = null,
    val mime: String = "video/mp4",
)

/**
 * Runs one long export at a time (video can take minutes) on a background thread that is
 * independent of any screen. [ExportService] keeps the app in the foreground while it runs,
 * so it continues if the user switches apps.
 */
object ExportJobs {
    var state by mutableStateOf<ExportState?>(null)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var cancelRequested = false

    val isRunning: Boolean get() = state?.done == false

    fun isExporting(projectId: String) = state?.let { !it.done && it.projectId == projectId } == true

    /**
     * Starts [work] (called on a background thread). It reports progress 0..1, should stop
     * with [ExportCancelled] when the cancel check returns true, and returns where the result
     * was saved. Must be called while the app is visible (it starts a foreground service).
     */
    fun start(
        context: Context,
        projectId: String,
        title: String,
        mime: String,
        work: (progress: (Float) -> Unit, cancelled: () -> Boolean) -> Pair<String, Uri?>,
    ) {
        if (isRunning) return
        cancelRequested = false
        state = ExportState(projectId, title, mime = mime)
        ContextCompat.startForegroundService(context, Intent(context, ExportService::class.java))
        scope.launch {
            var lastPct = -1
            try {
                val (path, uri) = work(
                    { p ->
                        val pct = (p * 100).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            state = state?.copy(progress = p)
                        }
                    },
                    { cancelRequested },
                )
                state = state?.copy(progress = 1f, done = true, resultPath = path, resultUri = uri)
            } catch (e: ExportCancelled) {
                // The user asked for it (in the app or from the notification): nothing to report.
                state = null
            } catch (e: Throwable) {
                state = state?.copy(done = true, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancel() {
        cancelRequested = true
    }

    /** Clears a finished export's result from the screen. */
    fun dismiss() {
        if (state?.done == true) state = null
    }
}
