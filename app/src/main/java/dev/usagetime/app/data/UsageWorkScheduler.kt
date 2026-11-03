package dev.usagetime.app.data

import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

internal class UsageWorkScheduler(
    private val enqueuePeriodic: () -> Unit,
    private val enqueueCatchUp: () -> Unit,
) {
    fun schedule() = enqueuePeriodic()
    fun catchUp() = enqueueCatchUp()

    fun onBroadcast(action: String?) {
        if (action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_USER_UNLOCKED)) {
            schedule()
            catchUp()
        }
    }
}

// Keep Android construction outside the coordinator so routing can run on a plain JVM.
internal fun usageWorkScheduler(context: Context) = UsageWorkScheduler(
    enqueuePeriodic = {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "save-usage", ExistingPeriodicWorkPolicy.KEEP, periodicUsageRequest(),
        )
    },
    enqueueCatchUp = {
        // KEEP coalesces broadcasts without replacing a running collection.
        WorkManager.getInstance(context).enqueueUniqueWork(
            "catch-up-usage", ExistingWorkPolicy.KEEP, catchUpUsageRequest(),
        )
    },
)

internal fun periodicUsageRequest() =
    PeriodicWorkRequestBuilder<UsageWorker>(6, TimeUnit.HOURS)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build()

internal fun catchUpUsageRequest() =
    OneTimeWorkRequestBuilder<UsageWorker>()
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
