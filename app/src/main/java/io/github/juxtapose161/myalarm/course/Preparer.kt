package io.github.juxtapose161.myalarm.course

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.juxtapose161.myalarm.SettingsStore
import io.github.juxtapose161.myalarm.anki.AnkiRepository
import io.github.juxtapose161.myalarm.llm.ClaudeClient
import java.util.concurrent.TimeUnit

/**
 * Prepares tasks in advance (while the phone is online, e.g. in the evening on Wi-Fi), so that
 * at the alarm only the answer has to be checked.
 */
object Preparer {
    private const val TAG = "Preparer"
    private const val PERIODIC = "prepare-periodic"
    private const val NOW = "prepare-now"
    /** Prepared tasks kept per kind. */
    const val STOCK = 2
    private const val MAX_AGE_MS = 14L * 24 * 3600 * 1000

    fun schedule(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = PeriodicWorkRequestBuilder<PrepareWorker>(4, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun runNow(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<PrepareWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
    }

    /** Kinds used by any scenario that can be prepared in advance. */
    fun wantedKinds(store: CourseStore): List<TaskKind> =
        store.scenarios.flatMap { it.kindList }.filter { it.pregenerated }.distinct()

    /** Candidate words for a task: last mistakes and due cards first. */
    fun candidateWords(context: Context): List<String> {
        val repo = AnkiRepository(context)
        if (!repo.isReady()) return emptyList()
        val settings = SettingsStore(context)
        return repo.loadWords(settings.load(), settings.mistakes, total = 60).map { it.main }
    }

    /** Fills the cache up to [STOCK] tasks per kind. Returns how many tasks were prepared. */
    suspend fun fill(context: Context, onProgress: (String) -> Unit = {}): Int {
        val store = CourseStore(context)
        val claude = store.claude
        if (!claude.enabled) return 0
        val tutor = Tutor(ClaudeClient(claude), store)
        val words = candidateWords(context)
        var made = 0
        val now = System.currentTimeMillis()
        for (kind in wantedKinds(store)) {
            val level = store.profile.level
            store.prepared(kind, level).filter { now - it.second.createdAt > MAX_AGE_MS }.forEach { it.first.delete() }
            var have = store.prepared(kind, level).size
            while (have < STOCK) {
                onProgress("Готовлю: ${kind.title}…")
                val task = try { tutor.prepare(kind, words.take(15).shuffled() + words.drop(15).shuffled()) } catch (e: Exception) {
                    Log.w(TAG, "Cannot prepare $kind", e)
                    onProgress("Не получилось: ${kind.title} — ${e.message?.take(120)}")
                    return made
                }
                store.addPrepared(task)
                have++
                made++
            }
        }
        return made
    }
}

class PrepareWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        Preparer.fill(applicationContext)
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }
}
