package dev.cinematic.p0

import android.content.Context

/** Small device-local probe settings; no media or cloud migration. */
class CapturePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("capture-probe", Context.MODE_PRIVATE)
    fun text(key: String, fallback: String = "") = preferences.getString(key, fallback) ?: fallback
    fun flag(key: String, fallback: Boolean = false) = preferences.getBoolean(key, fallback)
    fun put(key: String, value: String) { preferences.edit().putString(key, value).apply() }
    fun put(key: String, value: Boolean) { preferences.edit().putBoolean(key, value).apply() }
    fun mode(): CaptureMode = runCatching { CaptureMode.valueOf(text("mode", "STILL")) }.getOrDefault(CaptureMode.STILL)
}
