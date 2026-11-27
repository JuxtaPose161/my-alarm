package io.github.juxtapose161.myalarm.course

import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * Which tasks run when Sleep as Android opens the captcha. The app cannot see which alarm
 * started it, so the scenario is picked by the time of day: a morning alarm and a daytime
 * "task on schedule" alarm get different tasks.
 */
@Serializable
data class Scenario(
    val id: String,
    val name: String,
    /** Window start, minutes after midnight. */
    val from: Int,
    /** Window end, minutes after midnight (may be less than [from]: the window wraps midnight). */
    val to: Int,
    /** Quick cards before the main task, to wake up. 0 = none. */
    val warmupCards: Int,
    /** Task kinds to choose from (TaskKind names). */
    val kinds: List<String>,
    /** How many main tasks in a row. */
    val tasks: Int = 1,
    /** After this many minutes a "math instead" button appears. 0 = never. */
    val escapeMinutes: Int,
) {
    fun contains(minuteOfDay: Int): Boolean =
        if (from <= to) minuteOfDay in from until to else minuteOfDay >= from || minuteOfDay < to

    val kindList: List<TaskKind> get() = kinds.mapNotNull { TaskKind.byName(it) }

    companion object {
        val defaults = listOf(
            Scenario(
                id = "morning", name = "Утренний будильник", from = 4 * 60, to = 11 * 60,
                warmupCards = 3,
                kinds = listOf(TaskKind.TRANSLATE, TaskKind.DIALOG, TaskKind.RETELL, TaskKind.DAY_PLAN, TaskKind.ALIAS)
                    .map { it.name },
                escapeMinutes = 15,
            ),
            Scenario(
                id = "day", name = "Задание по расписанию", from = 11 * 60, to = 4 * 60,
                warmupCards = 0,
                kinds = listOf(
                    TaskKind.NEGOTIATION, TaskKind.REPHRASE, TaskKind.ERRORS, TaskKind.BACK_TRANSLATE,
                    TaskKind.CUE_CARD, TaskKind.YESTERDAY, TaskKind.DICTATION, TaskKind.RETELL, TaskKind.DIALOG,
                ).map { it.name },
                escapeMinutes = 30,
            ),
        )

        fun pick(scenarios: List<Scenario>, minuteOfDay: Int): Scenario =
            scenarios.firstOrNull { it.contains(minuteOfDay) } ?: scenarios.firstOrNull() ?: defaults[0]

        /**
         * Chooses the next kind: the one done least recently, ties broken at random.
         * Kinds that need Claude are skipped when it is not available.
         */
        fun chooseKind(
            kinds: List<TaskKind>,
            history: List<LearnerProfile.HistoryEntry>,
            claudeAvailable: Boolean,
            ankiAvailable: Boolean,
            random: Random = Random.Default,
        ): TaskKind {
            val usable = kinds.filter { (claudeAvailable || !it.needsClaude) && (ankiAvailable || !it.needsAnki) }
            if (usable.isEmpty()) return if (ankiAvailable) TaskKind.TRANSLATE else TaskKind.QUICK
            fun lastUse(k: TaskKind) = history.indexOfLast { it.kind == k.name }
            val oldest = usable.minOf { lastUse(it) }
            val candidates = usable.filter { lastUse(it) == oldest }
            return candidates[random.nextInt(candidates.size)]
        }

        fun format(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
    }
}

/** Translate and Alias are built from Anki words only. */
val TaskKind.needsAnki get() = this == TaskKind.TRANSLATE || this == TaskKind.ALIAS
