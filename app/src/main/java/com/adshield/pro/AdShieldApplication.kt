package com.adshield.pro

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.adshield.pro.data.local.preferences.UserPreferencesRepository
import com.adshield.pro.update.GitHubUpdateWorker
import com.adshield.pro.worker.FilterUpdateWorker
import com.adshield.pro.worker.SmartOptimizationWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class AdShieldApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var preferencesRepository: UserPreferencesRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        GitHubUpdateWorker.schedule(this)
        appScope.launch {
            val settings = preferencesRepository.getSettingsSnapshot()
            FilterUpdateWorker.scheduleOrCancelPeriodicUpdates(
                context = this@AdShieldApplication,
                enabled = settings.autoUpdateFiltersEnabled,
                intervalHours = settings.updateIntervalHours
            )
            SmartOptimizationWorker.scheduleOrCancelSmartMaintenance(
                context = this@AdShieldApplication,
                enabled = settings.smartAutoOptimizeEnabled,
                intervalHours = settings.autoJunkCleanIntervalHours
            )
        }
    }
}
