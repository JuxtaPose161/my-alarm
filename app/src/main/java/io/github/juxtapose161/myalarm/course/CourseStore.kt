package io.github.juxtapose161.myalarm.course

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

/** Where the phone and the scheduled Claude session exchange files. */
@Serializable
data class SyncSettings(
    /** "owner/name" of the private data repository. */
    val repo: String = "",
    /** Fine-grained GitHub token with Contents read/write on that repository only. */
    val token: String = "",
    val lastSync: Long = 0,
    val lastError: String = "",
    /** Hash of the last published phone state, to skip unchanged uploads. */
    val publishedHash: Int = 0,
) {
    val enabled get() = repo.contains('/') && token.isNotBlank()
}

/** A local record of a finished task, used to rotate task kinds. */
@Serializable
data class LocalHistory(val day: String, val kind: String, val title: String, val passed: Boolean)

/**
 * Local storage: settings, scenarios, prepared tasks synced from the data repository,
 * answers waiting to be uploaded, the learner profile and reviews written by Claude.
 */
class CourseStore(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "course").apply { mkdirs() }
    private val tasksDir = File(dir, "tasks").apply { mkdirs() }
    private val outboxDir = File(dir, "outbox").apply { mkdirs() }
    private val reviewsDir = File(dir, "reviews").apply { mkdirs() }

    private inline fun <reified T> read(file: File, default: () -> T): T = try {
        if (file.exists()) AppJson.decodeFromString<T>(file.readText()) else default()
    } catch (e: Exception) {
        Log.w(TAG, "Cannot read ${file.name}", e)
        default()
    }

    private inline fun <reified T> write(file: File, value: T) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(AppJson.encodeToString(value))
        tmp.renameTo(file)
    }

    private fun f(name: String) = File(dir, name)

    var level: String
        get() = read(f("level.json")) { "B1" }
        set(v) = write(f("level.json"), v)

    var scenarios: List<Scenario>
        get() = read(f("scenarios.json")) { Scenario.defaults }.ifEmpty { Scenario.defaults }
        set(v) = write(f("scenarios.json"), v)

    var params: TaskParams
        get() = read(f("params.json")) { TaskParams() }
        set(v) = write(f("params.json"), v)

    var sync: SyncSettings
        get() = read(f("sync.json")) { SyncSettings() }
        set(v) = write(f("sync.json"), v)

    /** Written by Claude, downloaded from the data repository. */
    var profile: LearnerProfile
        get() = read(f("profile.json")) { LearnerProfile(level = level) }
        set(v) = write(f("profile.json"), v)

    @get:Synchronized @set:Synchronized
    var history: List<LocalHistory>
        get() = read(f("history.json")) { emptyList() }
        set(v) = write(f("history.json"), v.takeLast(100))

    @Synchronized
    fun addHistory(h: LocalHistory) { history = history + h }

    /** Ids of prepared tasks already used on this phone (never shown twice). */
    @get:Synchronized @set:Synchronized
    var usedTasks: Set<String>
        get() = read(f("used.json")) { emptySet<String>() }
        set(v) = write(f("used.json"), v.toList().takeLast(500).toSet())

    // --- prepared tasks ---

    @Synchronized
    fun saveTask(t: TaskFile) = write(File(tasksDir, "${safe(t.id)}.json"), t)

    @Synchronized
    fun hasTask(id: String) = File(tasksDir, "${safe(id)}.json").exists()

    @Synchronized
    fun tasks(): List<TaskFile> =
        (tasksDir.listFiles { x -> x.name.endsWith(".json") } ?: emptyArray()).sortedBy { it.name }
            .mapNotNull { file -> read<TaskFile?>(file) { null }.also { if (it == null) file.delete() } }

    /** Takes the oldest unused task of this kind (at the current level, if there is one). */
    @Synchronized
    fun takeTask(kind: TaskKind): TaskFile? {
        val all = tasks().filter { it.kind == kind.name && it.id !in usedTasks }
        val t = all.sortedBy { it.createdAt }.let { list -> list.firstOrNull { it.level == level } ?: list.firstOrNull() } ?: return null
        usedTasks = usedTasks + t.id
        File(tasksDir, "${safe(t.id)}.json").delete()
        return t
    }

    @Synchronized
    fun stock(): Map<TaskKind, Int> {
        val list = tasks().filter { it.id !in usedTasks }
        return TaskKind.entries.filter { it.prepared }.associateWith { k -> list.count { it.kind == k.name } }
    }

    @Synchronized
    fun dropTasksNotIn(remoteIds: Set<String>) {
        tasks().filter { it.id !in remoteIds }.forEach { File(tasksDir, "${safe(it.id)}.json").delete() }
    }

    // --- answers waiting for upload ---

    @Synchronized
    fun queueAnswer(a: AnswerFile) = write(File(outboxDir, "${safe(a.taskId)}.json"), a)

    @Synchronized
    fun outbox(): List<Pair<File, AnswerFile>> =
        (outboxDir.listFiles { x -> x.name.endsWith(".json") } ?: emptyArray()).sortedBy { it.name }
            .mapNotNull { file -> read<AnswerFile?>(file) { null }?.let { file to it } }

    // --- reviews from Claude ---

    @Synchronized
    fun saveReview(r: ReviewFile) {
        write(File(reviewsDir, "${safe(r.taskId)}.json"), r)
        val files = reviewsDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        files.dropLast(MAX_REVIEWS).forEach { it.delete() }
    }

    @Synchronized
    fun hasReview(id: String) = File(reviewsDir, "${safe(id)}.json").exists()

    @Synchronized
    fun reviews(): List<ReviewFile> =
        (reviewsDir.listFiles() ?: emptyArray()).sortedByDescending { it.lastModified() }
            .mapNotNull { read<ReviewFile?>(it) { null } }

    companion object {
        private const val TAG = "CourseStore"
        private const val MAX_REVIEWS = 60
        fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    }
}
