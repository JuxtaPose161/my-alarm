package io.github.juxtapose161.myalarm.course

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

/** Claude connection settings. */
@Serializable
data class ClaudeSettings(
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    /** Ask Claude when a translation in task 1 is not on the card. */
    val checkSynonyms: Boolean = true,
) {
    val enabled get() = apiKey.isNotBlank()

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
    }
}

/**
 * Storage for the course: learner profile, scenarios, task sizes, Claude settings and the cache of
 * prepared tasks. Everything is small JSON in the app's private files.
 */
class CourseStore(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "course").apply { mkdirs() }
    private val cacheDir = File(dir, "prepared").apply { mkdirs() }

    private inline fun <reified T> read(name: String, default: () -> T): T = try {
        val f = File(dir, name)
        if (f.exists()) AppJson.decodeFromString<T>(f.readText()) else default()
    } catch (e: Exception) {
        Log.w(TAG, "Cannot read $name", e)
        default()
    }

    private inline fun <reified T> write(name: String, value: T) {
        val f = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(AppJson.encodeToString(value))
        tmp.renameTo(f)
    }

    @get:Synchronized @set:Synchronized
    var profile: LearnerProfile
        get() = read("profile.json") { LearnerProfile() }
        set(v) = write("profile.json", v)

    @Synchronized
    fun updateProfile(change: (LearnerProfile) -> LearnerProfile) {
        profile = change(profile)
    }

    var scenarios: List<Scenario>
        get() = read("scenarios.json") { Scenario.defaults }.ifEmpty { Scenario.defaults }
        set(v) = write("scenarios.json", v)

    var params: TaskParams
        get() = read("params.json") { TaskParams() }
        set(v) = write("params.json", v)

    var claude: ClaudeSettings
        get() = read("claude.json") { ClaudeSettings() }
        set(v) = write("claude.json", v)

    // --- prepared tasks ---

    @Synchronized
    fun addPrepared(task: PreparedTask) {
        File(cacheDir, "${task.kind}_${task.createdAt}.json").writeText(AppJson.encodeToString(task))
    }

    /** Prepared tasks of this kind for this level, oldest first. */
    @Synchronized
    fun prepared(kind: TaskKind, level: String): List<Pair<File, PreparedTask>> =
        (cacheDir.listFiles { f -> f.name.startsWith("${kind.name}_") && f.name.endsWith(".json") } ?: emptyArray())
            .sortedBy { it.name }
            .mapNotNull { f ->
                try { f to AppJson.decodeFromString<PreparedTask>(f.readText()) } catch (e: Exception) {
                    f.delete(); null
                }
            }
            .filter { it.second.level == level }

    /** Takes (and removes) the oldest prepared task. */
    @Synchronized
    fun takePrepared(kind: TaskKind, level: String): PreparedTask? {
        val (file, task) = prepared(kind, level).firstOrNull() ?: return null
        file.delete()
        return task
    }

    @Synchronized
    fun preparedCount(): Map<TaskKind, Int> =
        TaskKind.entries.filter { it.pregenerated }.associateWith { prepared(it, profile.level).size }

    @Synchronized
    fun clearPrepared() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        private const val TAG = "CourseStore"
    }
}
