package io.github.juxtapose161.myalarm.course

import kotlinx.serialization.Serializable

/**
 * The learner profile: the tutor's memory. It lives in the data repository as profile.json and is
 * maintained by the scheduled Claude session (see tutor/TUTOR.md); the phone only shows it.
 */
@Serializable
data class LearnerProfile(
    val level: String = "B1",
    val updatedAt: String = "",
    /** Claude's notes about the learner, in Russian. */
    val summary: String = "",
    /** Curriculum grammar id -> stats. */
    val grammar: Map<String, GrammarStat> = emptyMap(),
    /** Lowercased English word -> stats. */
    val words: Map<String, WordStat> = emptyMap(),
    val strengths: List<String> = emptyList(),
    val weaknesses: List<String> = emptyList(),
    /** What the next tasks will focus on, in Russian. */
    val nextFocus: List<String> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
) {
    @Serializable
    data class GrammarStat(val ok: Int = 0, val errors: Int = 0, val lastDay: String = "")

    @Serializable
    data class WordStat(val used: Int = 0, val missed: Int = 0)

    @Serializable
    data class HistoryEntry(val day: String, val kind: String, val title: String, val passed: Boolean, val note: String = "")

    fun isMastered(id: String): Boolean {
        val s = grammar[id] ?: return false
        return s.ok >= MASTERED_OK && s.errors * 2 <= s.ok
    }

    /** Words the learner often gets wrong, most troublesome first. */
    fun struggling(limit: Int): List<String> =
        words.entries.filter { it.value.missed > 0 }
            .sortedByDescending { it.value.missed - it.value.used / 3.0 }
            .take(limit).map { it.key }

    companion object {
        const val MASTERED_OK = 3
    }
}
