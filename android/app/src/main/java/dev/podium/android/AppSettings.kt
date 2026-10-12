package dev.podium.android

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** The few preferences the Settings tab keeps. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("podium_settings", Context.MODE_PRIVATE)

    /** One of the AppCompatDelegate.MODE_NIGHT_* values. */
    var themeMode: Int
        get() = prefs.getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(value) = prefs.edit().putInt(KEY_THEME, value).apply()

    var dynamicColor: Boolean
        get() = prefs.getBoolean(KEY_DYNAMIC, true)
        set(value) = prefs.edit().putBoolean(KEY_DYNAMIC, value).apply()

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_DYNAMIC = "dynamic_color"
    }
}
