package com.example.phonebilling.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.phonebilling.domain.BillingRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class BillingSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: BillingRepository
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        repository.syncPendingLogs().fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() }
        )
}
