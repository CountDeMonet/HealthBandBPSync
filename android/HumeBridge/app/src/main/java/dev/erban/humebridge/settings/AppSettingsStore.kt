package dev.erban.humebridge.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val PREFS_NAME = "band_bp_sync_settings"
private const val KEY_APP_MODE = "app_mode"
private const val KEY_BACKGROUND_SYNC_ENABLED = "background_sync_enabled"
private const val KEY_BACKGROUND_SYNC_LAST_ATTEMPT_MS = "background_sync_last_attempt_ms"
private const val KEY_BACKGROUND_SYNC_NEXT_RUN_MS = "background_sync_next_run_ms"
private const val KEY_BACKGROUND_SYNC_LAST_STATUS = "background_sync_last_status"

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

data class BackgroundSyncSettings(
    val enabled: Boolean = false,
    val lastAttemptEpochMillis: Long? = null,
    val nextRunEpochMillis: Long? = null,
    val lastStatus: String? = null,
)

data class AppSettingsState(
    val appMode: AppMode = AppMode.COMPANION,
    val backgroundSync: BackgroundSyncSettings = BackgroundSyncSettings(),
)

class AppSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutableSettings = MutableStateFlow(readState())

    val settings: StateFlow<AppSettingsState> = mutableSettings

    fun setAppMode(mode: AppMode) {
        prefs.edit().putString(KEY_APP_MODE, mode.storageValue).apply()
        refresh()
    }

    fun setBackgroundSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BACKGROUND_SYNC_ENABLED, enabled).apply()
        refresh()
    }

    fun recordBackgroundSyncScheduled(nextRunEpochMillis: Long) {
        prefs.edit()
            .putLong(KEY_BACKGROUND_SYNC_NEXT_RUN_MS, nextRunEpochMillis)
            .apply()
        refresh()
    }

    fun recordBackgroundSyncAttempt(status: String) {
        prefs.edit()
            .putLong(KEY_BACKGROUND_SYNC_LAST_ATTEMPT_MS, System.currentTimeMillis())
            .putString(KEY_BACKGROUND_SYNC_LAST_STATUS, status)
            .apply()
        refresh()
    }

    fun refresh() {
        mutableSettings.value = readState()
    }

    private fun readState(): AppSettingsState = AppSettingsState(
        appMode = AppMode.fromStorageValue(prefs.getString(KEY_APP_MODE, null)),
        backgroundSync = BackgroundSyncSettings(
            enabled = prefs.getBoolean(KEY_BACKGROUND_SYNC_ENABLED, false),
            lastAttemptEpochMillis = prefs.getLongOrNull(KEY_BACKGROUND_SYNC_LAST_ATTEMPT_MS),
            nextRunEpochMillis = prefs.getLongOrNull(KEY_BACKGROUND_SYNC_NEXT_RUN_MS),
            lastStatus = prefs.getString(KEY_BACKGROUND_SYNC_LAST_STATUS, null),
        ),
    )

    private fun android.content.SharedPreferences.getLongOrNull(key: String): Long? =
        if (contains(key)) getLong(key, 0L) else null
}
