package dev.erban.humebridge.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val PREFS_NAME = "band_bp_sync_settings"
private const val KEY_APP_MODE = "app_mode"

enum class AppMode(
    val storageValue: String,
    val displayName: String,
    val shortDescription: String,
) {
    COMPANION(
        storageValue = "companion",
        displayName = "Companion",
        shortDescription = "Work alongside the vendor app; manual BP gap filling only.",
    ),
    STANDALONE(
        storageValue = "standalone",
        displayName = "Standalone",
        shortDescription = "Prepare for primary sync, richer local views, and opt-in metric expansion.",
    );

    companion object {
        fun fromStorageValue(value: String?): AppMode = entries.firstOrNull { it.storageValue == value } ?: COMPANION
    }
}

class AppSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutableAppMode = MutableStateFlow(AppMode.fromStorageValue(prefs.getString(KEY_APP_MODE, null)))

    val appMode: StateFlow<AppMode> = mutableAppMode

    fun setAppMode(mode: AppMode) {
        prefs.edit().putString(KEY_APP_MODE, mode.storageValue).apply()
        mutableAppMode.value = mode
    }
}

