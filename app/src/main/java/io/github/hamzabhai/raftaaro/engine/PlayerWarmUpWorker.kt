package io.github.hamzabhai.raftaaro.engine

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.hamzabhai.raftaaro.core.YtDlpError
import io.github.hamzabhai.raftaaro.graph
import java.util.concurrent.TimeUnit

/**
 * Once a day on Wi-Fi: updates yt-dlp when due and reads a short video, so the cache holds
 * YouTube's current player. YouTube changes players every few days, and without this the first
 * video after a change waits ~36 s while the player is fetched and prepared.
 */
class PlayerWarmUpWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.graph
        val engine = graph.engine
        return try {
            engine.ensureReady()
            // the app's own start-up update may be running; a new yt-dlp brings its own player code
            graph.warmUpJob?.join()
            runCatching { engine.updateIfDue(graph.settings) }
            runCatching { engine.precompileIfNeeded() }
            val ms = engine.warmPlayerCache()
            Log.i("Raftaaro", "player cache warmed in $ms ms")
            Result.success()
        } catch (e: YtDlpError) {
            Log.i("Raftaaro", "player cache not warmed: ${e.message}")
            if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }

    companion object {
        const val NAME = "player-warm-up"

        /** Safe to call on every start: an already scheduled warm-up keeps its timing. */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<PlayerWarmUpWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
