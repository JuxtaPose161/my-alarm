package io.github.juxtapose161.myalarm.course

import kotlinx.serialization.Serializable

/**
 * What the app knows about the learner. It is the "memory" sent with every request to Claude:
 * the course position, grammar statistics, struggling words, recent tasks and a short summary
 * that Claude rewrites after every checked task.
 */
@Serializable
data class LearnerProfile(
    val level: String = "B1",
    /** Claude's running notes about the learner, in Russian. */
    val summary: String = "",
    val grammar: Map<String, GrammarStat> = emptyMap(),
    /** Lowercased English word -> stats. */
    val words: Map<String, WordStat> = emptyMap(),
    val history: List<HistoryEntry> = emptyList(),
    val strengths: List<String> = emptyList(),
    val weaknesses: List<String> = emptyList(),
    /** Rotates the topics of the level. */
    val topicCursor: Int = 0,
) {
    @Serializable
    data class GrammarStat(val ok: Int = 0, val errors: Int = 0, val lastDay: String = "")

    @Serializable
    data class WordStat(val used: Int = 0, val missed: Int = 0)

    @Serializable
    data class HistoryEntry(
        val day: String,
        val kind: String,
        val title: String,
        val passed: Boolean,
        val note: String = "",
    )

    fun isMastered(id: String): Boolean {
        val s = grammar[id] ?: return false
        return s.ok >= MASTERED_OK && s.errors * 2 <= s.ok
    }

    /**
     * Grammar to practise next: the first not-yet-mastered points of the current level,
     * plus the weakest point from the levels below (review).
     */
    fun focusGrammar(count: Int): List<Curriculum.Grammar> {
        val level = Curriculum.level(level)
        val current = level.grammar.filter { !isMastered(it.id) }
            .ifEmpty { level.grammar.sortedBy { grammar[it.id]?.ok ?: 0 } }
        val below = Curriculum.grammarUpTo(this.level).filter { it !in level.grammar }
        val weakBelow = below
            .filter { (grammar[it.id]?.errors ?: 0) > 0 && !isMastered(it.id) }
            .maxByOrNull { grammar[it.id]?.errors ?: 0 }
        val picked = current.take(if (weakBelow != null) count - 1 else count).toMutableList()
        if (weakBelow != null && count > 0) picked += weakBelow
        return picked.take(count.coerceAtLeast(0))
    }

    fun topic(): String {
        val topics = Curriculum.level(level).topics
        return topics[Math.floorMod(topicCursor, topics.size)]
    }

    /** Merges the result of one checked task. */
    fun withEvaluation(day: String, kind: TaskKind, title: String, e: Evaluation): LearnerProfile =
        withResult(day, kind, title, e.passed, e.grammarOk, e.grammarErrors, e.usedWords, e.weaknesses, e.strengths,
            note = e.feedbackRu.take(160))

    fun withResult(
        day: String,
        kind: TaskKind,
        title: String,
        passed: Boolean,
        grammarOk: List<String> = emptyList(),
        grammarErrors: List<String> = emptyList(),
        usedWords: List<String> = emptyList(),
        weak: List<String> = emptyList(),
        strong: List<String> = emptyList(),
        missedWords: List<String> = emptyList(),
        note: String = "",
    ): LearnerProfile {
        val g = grammar.toMutableMap()
        for (id in grammarOk.distinct()) {
            if (Curriculum.grammar(id) == null) continue
            val s = g[id] ?: GrammarStat()
            g[id] = s.copy(ok = s.ok + 1, lastDay = day)
        }
        for (id in grammarErrors.distinct()) {
            if (Curriculum.grammar(id) == null) continue
            val s = g[id] ?: GrammarStat()
            g[id] = s.copy(errors = s.errors + 1, lastDay = day)
        }
        val w = words.toMutableMap()
        for (word in usedWords.map { it.lowercase().trim() }.filter { it.isNotEmpty() }.distinct()) {
            val s = w[word] ?: WordStat()
            w[word] = s.copy(used = s.used + 1)
        }
        for (word in missedWords.map { it.lowercase().trim() }.filter { it.isNotEmpty() }.distinct()) {
            val s = w[word] ?: WordStat()
            w[word] = s.copy(missed = s.missed + 1)
        }
        return copy(
            grammar = g,
            words = w.entries.sortedByDescending { it.value.missed * 3 + it.value.used }.take(MAX_WORDS)
                .associate { it.key to it.value },
            history = (history + HistoryEntry(day, kind.name, title, passed, note)).takeLast(MAX_HISTORY),
            strengths = (strengths + strong).map { it.trim() }.filter { it.isNotEmpty() }.distinct().takeLast(MAX_NOTES),
            weaknesses = (weaknesses + weak).map { it.trim() }.filter { it.isNotEmpty() }.distinct().takeLast(MAX_NOTES),
            topicCursor = topicCursor + 1,
        )
    }

    /** Words the learner often gets wrong, most troublesome first. */
    fun struggling(limit: Int): List<String> =
        words.entries.filter { it.value.missed > 0 }
            .sortedByDescending { it.value.missed - it.value.used / 3.0 }
            .take(limit).map { it.key }

    /** Text block describing the learner for prompts. */
    fun describe(): String = buildString {
        val lvl = Curriculum.level(level)
        appendLine("Level: $level (${lvl.book}).")
        if (summary.isNotBlank()) appendLine("Tutor notes: $summary")
        val mastered = Curriculum.grammarUpTo(level).filter { isMastered(it.id) }.map { it.id }
        if (mastered.isNotEmpty()) appendLine("Grammar already used confidently: ${mastered.joinToString()}")
        val weakGrammar = grammar.entries.filter { it.value.errors > 0 }
            .sortedByDescending { it.value.errors - it.value.ok / 2.0 }.take(6)
            .map { "${it.key} (errors ${it.value.errors}, ok ${it.value.ok})" }
        if (weakGrammar.isNotEmpty()) appendLine("Grammar with mistakes: ${weakGrammar.joinToString()}")
        if (weaknesses.isNotEmpty()) appendLine("Weak points: ${weaknesses.takeLast(6).joinToString("; ")}")
        if (strengths.isNotEmpty()) appendLine("Strong points: ${strengths.takeLast(4).joinToString("; ")}")
        val hard = struggling(10)
        if (hard.isNotEmpty()) appendLine("Words often missed: ${hard.joinToString()}")
        val recent = history.takeLast(8)
        if (recent.isNotEmpty()) {
            appendLine("Recent tasks (avoid repeating them):")
            recent.forEach { appendLine("- ${it.day} ${it.kind}: ${it.title} — ${if (it.passed) "passed" else "failed"}") }
        }
    }.trim()

    companion object {
        const val MASTERED_OK = 3
        const val MAX_HISTORY = 60
        const val MAX_WORDS = 400
        const val MAX_NOTES = 12
    }
}
