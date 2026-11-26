package io.github.juxtapose161.myalarm

import android.content.Context
import io.github.juxtapose161.myalarm.quiz.TaskType

/** User settings. Empty field names mean "first field" / "second field" / "no example". */
data class Settings(
    val deck: String = "",
    val level: String = "",          // "", A1..C2 — adds a tag filter
    val extraFilter: String = "",    // any Anki search, e.g. "tag:verbs"
    val enField: String = "",
    val ruField: String = "",
    val exampleField: String = "",
    val types: Set<TaskType> = TaskType.entries.toSet(),
    val tagMistakesInAnki: Boolean = false,
) {
    /** Anki browser search for the configured words (without due/limit parts). */
    fun ankiQuery(): String = buildList {
        if (deck.isNotBlank()) add("\"deck:${deck.replace("\"", "\\\"")}\"")
        add("-is:suspended")
        if (level.isNotBlank()) add("(tag:$level OR tag:*::$level)")
        if (extraFilter.isNotBlank()) add("(${extraFilter.trim()})")
    }.joinToString(" ")

    companion object {
        val LEVELS = listOf("", "A1", "A2", "B1", "B2", "C1", "C2")
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): Settings = Settings(
        deck = prefs.getString("deck", "") ?: "",
        level = prefs.getString("level", "") ?: "",
        extraFilter = prefs.getString("extraFilter", "") ?: "",
        enField = prefs.getString("enField", "") ?: "",
        ruField = prefs.getString("ruField", "") ?: "",
        exampleField = prefs.getString("exampleField", "") ?: "",
        types = prefs.getStringSet("types", null)
            ?.mapNotNull { n -> TaskType.entries.firstOrNull { it.name == n } }?.toSet()
            ?: TaskType.entries.toSet(),
        tagMistakesInAnki = prefs.getBoolean("tagMistakes", false),
    )

    fun save(s: Settings) {
        prefs.edit()
            .putString("deck", s.deck)
            .putString("level", s.level)
            .putString("extraFilter", s.extraFilter)
            .putString("enField", s.enField)
            .putString("ruField", s.ruField)
            .putString("exampleField", s.exampleField)
            .putStringSet("types", s.types.map { it.name }.toSet())
            .putBoolean("tagMistakes", s.tagMistakesInAnki)
            .apply()
    }

    /** Notes missed on previous mornings; they are asked first next time. */
    var mistakes: Set<Long>
        get() = prefs.getStringSet("mistakes", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
        set(v) { prefs.edit().putStringSet("mistakes", v.take(200).map { it.toString() }.toSet()).apply() }

    /** Short log of past mornings: "yyyy-MM-dd|correct|missed". */
    fun addHistory(line: String) {
        val old = prefs.getString("history", "") ?: ""
        val lines = (old.lines().filter { it.isNotBlank() } + line).takeLast(60)
        prefs.edit().putString("history", lines.joinToString("\n")).apply()
    }

    fun history(): List<String> = (prefs.getString("history", "") ?: "").lines().filter { it.isNotBlank() }
}
