package com.notedown.domain

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.notedown.data.Settings
import com.notedown.data.DEFAULT_PROMPT
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint @InstallIn(SingletonComponent::class) interface WorkerDependencies { fun pipeline(): TranscriptionPipeline }
class TranscriptionWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.failure()
        val dependencies = EntryPointAccessors.fromApplication(applicationContext, WorkerDependencies::class.java)
        dependencies.pipeline().run(id, Settings(wav = inputData.getBoolean("wav", false), language = inputData.getString("language").orEmpty(),
            cleanup = inputData.getBoolean("cleanup", false), cleanupModel = inputData.getString("cleanupModel").orEmpty(),
            prompt = inputData.getString("prompt") ?: DEFAULT_PROMPT))
        // API failures are persisted and retried explicitly to avoid unexpected duplicate charges.
        return Result.success()
    }
}
