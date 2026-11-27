package io.github.juxtapose161.myalarm.data

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
import io.github.juxtapose161.myalarm.course.AppJson
import io.github.juxtapose161.myalarm.course.CourseStore
import io.github.juxtapose161.myalarm.course.LearnerProfile
import io.github.juxtapose161.myalarm.course.PhoneState
import io.github.juxtapose161.myalarm.course.PhoneWord
import io.github.juxtapose161.myalarm.course.ReviewFile
import io.github.juxtapose161.myalarm.course.TaskFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Exchanges files with the private data repository:
 * - publishes phone/state.json (level, task sizes, wanted kinds, candidate Anki words);
 * - uploads answers to answers/;
 * - downloads new tasks from tasks/, the profile and Claude's reviews from reviews/.
 * The scheduled Claude Code session works on the other side of the same repository.
 */
object Sync {
    private const val TAG = "Sync"
    private const val PERIODIC = "sync-periodic"
    private const val NOW = "sync-now"
    /** Prepared tasks the session keeps per kind. */
    const val STOCK = 2

    data class Report(val uploaded: Int, val downloaded: Int, val reviews: Int)

    fun schedule(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(3, TimeUnit.HOURS).setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Runs as soon as the network is available (e.g. after a captcha). */
    fun soon(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
    }

    fun now(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    fun phoneState(context: Context, store: CourseStore): PhoneState {
        val settings = SettingsStore(context)
        val repo = AnkiRepository(context)
        val mistakes = settings.mistakes
        val words = if (repo.isReady()) repo.loadWords(settings.load(), mistakes, total = 80) else emptyList()
        return PhoneState(
            updatedAt = now(),
            level = store.level,
            params = store.params,
            kinds = store.scenarios.flatMap { it.kindList }.filter { it.prepared }.map { it.name }.distinct(),
            stock = STOCK,
            words = words.map { PhoneWord(it.main, it.ru, it.example.orEmpty(), missed = it.noteId in mistakes) },
        )
    }

    suspend fun run(context: Context): Report = withContext(Dispatchers.IO) {
        val store = CourseStore(context)
        val s = store.sync
        require(s.enabled) { "Не настроена синхронизация" }
        val gh = GitHubRepo(s.repo.trim(), s.token)
        try {
            // 1. Phone state (only when it changed; the timestamp is not part of the hash).
            val state = phoneState(context, store)
            val hash = state.copy(updatedAt = "").hashCode()
            if (hash != s.publishedHash) {
                gh.write("phone/state.json", AppJson.encodeToString(state), "phone: state")
                store.sync = store.sync.copy(publishedHash = hash)
            }
            // 2. Answers.
            var uploaded = 0
            for ((file, a) in store.outbox()) {
                gh.write("answers/${CourseStore.safe(a.taskId)}.json", AppJson.encodeToString(a), "phone: answer ${a.kind}")
                file.delete()
                uploaded++
            }
            // 3. Tasks.
            val remote = gh.list("tasks").filter { it.type == "file" && it.name.endsWith(".json") }
            val used = store.usedTasks
            var downloaded = 0
            val remoteIds = mutableSetOf<String>()
            for (e in remote) {
                val id = e.name.removeSuffix(".json")
                remoteIds += id
                if (id in used || store.hasTask(id)) continue
                val text = gh.read(e.path)?.text ?: continue
                val task = runCatching { AppJson.decodeFromString(TaskFile.serializer(), text) }
                    .onFailure { Log.w(TAG, "Bad task ${e.name}", it) }.getOrNull() ?: continue
                if (task.taskKind == null) continue
                store.saveTask(task.copy(id = id))
                downloaded++
            }
            // Tasks the session removed (e.g. after a level change) disappear here too.
            store.dropTasksNotIn(remoteIds)
            // 4. Profile.
            gh.read("profile.json")?.let { p ->
                runCatching { AppJson.decodeFromString(LearnerProfile.serializer(), p.text) }.onSuccess { store.profile = it }
            }
            // 5. Reviews.
            var reviews = 0
            for (e in gh.list("reviews").filter { it.name.endsWith(".json") }) {
                val id = e.name.removeSuffix(".json")
                if (store.hasReview(id)) continue
                val text = gh.read(e.path)?.text ?: continue
                runCatching { AppJson.decodeFromString(ReviewFile.serializer(), text) }.onSuccess {
                    store.saveReview(it.copy(taskId = id)); reviews++
                }
            }
            store.sync = store.sync.copy(lastSync = System.currentTimeMillis(), lastError = "")
            Report(uploaded, downloaded, reviews)
        } catch (e: Exception) {
            store.sync = store.sync.copy(lastError = e.message?.take(200) ?: e.javaClass.simpleName)
            throw e
        }
    }

    /** Unique id for answers of tasks that were not prepared (translation, quick cards). */
    fun localId(kind: String) = "local-${stamp()}-${kind.lowercase()}"
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        if (CourseStore(applicationContext).sync.enabled) Sync.run(applicationContext)
        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}
