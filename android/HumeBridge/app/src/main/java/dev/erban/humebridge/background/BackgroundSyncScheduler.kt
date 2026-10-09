package dev.erban.humebridge.background

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dev.erban.humebridge.settings.AppSettingsStore
import java.util.concurrent.TimeUnit
import kotlin.random.Random

private const val UNIQUE_BACKGROUND_SYNC_WORK = "band_bp_sync_background_sync"
private const val MIN_DELAY_HOURS = 2
private const val MAX_DELAY_HOURS = 4

object BackgroundSyncScheduler {
    fun setEnabled(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        AppSettingsStore(appContext).setBackgroundSyncEnabled(enabled)
        if (enabled) {
            scheduleNext(appContext)
        } else {
            WorkManager.getInstance(appContext).cancelUniqueWork(UNIQUE_BACKGROUND_SYNC_WORK)
        }
    }

    fun scheduleNext(context: Context) {
        val appContext = context.applicationContext
        val delayMinutes = Random.nextLong(
            MIN_DELAY_HOURS * 60L,
            MAX_DELAY_HOURS * 60L + 1L,
        )
        val nextRunEpochMillis = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(delayMinutes)
        AppSettingsStore(appContext).recordBackgroundSyncScheduled(nextRunEpochMillis)

        val request = OneTimeWorkRequestBuilder<BackgroundSyncWorker>()
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .addTag(UNIQUE_BACKGROUND_SYNC_WORK)
            .build()

        WorkManager.getInstance(appContext).enqueueUniqueWork(
            UNIQUE_BACKGROUND_SYNC_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
