package com.adshield.pro.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.adshield.pro.data.repository.IntelligenceRecorder
import com.adshield.pro.data.worldmonitor.WorldMonitorDao
import com.adshield.pro.data.worldmonitor.WorldMonitorRepository
import com.adshield.pro.domain.worldmonitor.WorldIntelCategory
import com.adshield.pro.engine.intelligence.IntelligenceEventFactory
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Low-battery background synchronization & Watchtower geofence worker for World Monitor 2035.
 */
@HiltWorker
class WorldMonitorWatchWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: WorldMonitorRepository,
    private val dao: WorldMonitorDao,
    private val recorder: IntelligenceRecorder
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val startedAt = System.currentTimeMillis()
        return try {
            val regions = dao.getEnabledWatchRegions()
            val watchedCats = buildSet {
                add(WorldIntelCategory.DISASTER)
                add(WorldIntelCategory.WEATHER)
                add(WorldIntelCategory.CYBER)
                add(WorldIntelCategory.SPACE)
                regions.forEach { r ->
                    r.categoriesCsv.split(',').filter { it.isNotBlank() }.forEach { token ->
                        runCatching { add(WorldIntelCategory.valueOf(token.trim())) }
                    }
                }
            }

            val syncedProviders = repository.syncEnabledProviders(
                categories = watchedCats,
                viewport = null,
                force = false,
                notifyWatchtower = true
            )
            recorder.emit(
                IntelligenceEventFactory.automationJob(
                    jobLabel = "World Monitor Watchtower",
                    success = true,
                    detail = "$syncedProviders global feed(s) checked · ${regions.size} watch geofence(s) evaluated",
                    durationMs = System.currentTimeMillis() - startedAt,
                    timestampEpochMs = System.currentTimeMillis()
                )
            )
            Result.success()
        } catch (t: Throwable) {
            runCatching {
                recorder.emit(
                    IntelligenceEventFactory.automationJob(
                        jobLabel = "World Monitor Watchtower",
                        success = false,
                        detail = t.message ?: t::class.java.simpleName,
                        durationMs = System.currentTimeMillis() - startedAt,
                        timestampEpochMs = System.currentTimeMillis()
                    )
                )
            }
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "world_monitor_2035_watchtower_sync"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<WorldMonitorWatchWorker>(
                repeatInterval = 60,
                repeatIntervalTimeUnit = TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
