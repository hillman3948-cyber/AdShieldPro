package com.adshield.pro.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.adshield.pro.engine.platform.SystemPreferencesController
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Reliable WorkManager fallback executing scheduled Private DNS or USB Debugging auto-reverts
 * when their deadline expires.
 */
@HiltWorker
class AutoRevertTileWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val controller: SystemPreferencesController
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        controller.executeDueAutoReverts()
        return Result.success()
    }
}
