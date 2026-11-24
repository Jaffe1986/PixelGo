package com.pixelgo.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** App-wide preferences (kept on the phone in SharedPreferences). */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("pixelgo", Context.MODE_PRIVATE)

    /** When true, "duplicate frame" puts the copy at the end of the animation. */
    var duplicateToEnd by mutableStateOf(prefs.getBoolean(KEY_DUP_TO_END, false))
        private set

    fun updateDuplicateToEnd(value: Boolean) {
        duplicateToEnd = value
        prefs.edit().putBoolean(KEY_DUP_TO_END, value).apply()
    }

    /**
     * How far off (ms) the audio timestamps were when the count-in last lined up a recording on
     * this speaker/microphone [route]; null if never measured. Used when there's no count-in
     * to measure (count-in off, or a route where the mic can't hear the ticks).
     */
    fun recordCorrectionMs(route: String): Float? =
        if (prefs.contains(KEY_REC_CORRECTION + route)) prefs.getFloat(KEY_REC_CORRECTION + route, 0f) else null

    fun setRecordCorrectionMs(route: String, ms: Float) {
        prefs.edit().putFloat(KEY_REC_CORRECTION + route, ms).apply()
    }

    companion object {
        private const val KEY_DUP_TO_END = "duplicate_to_end"
        private const val KEY_REC_CORRECTION = "record_correction_"
    }
}
