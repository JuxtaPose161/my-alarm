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
                kinds = listOf(TaskKind.TRANSLATE, TaskKind.DIALOG, TaskKind.RETELL, TaskKind.DAY_PLAN)
                    .map { it.name },
                escapeMinutes = 15,
            ),
            Scenario(
                id = "day", name = "Задание по расписанию", from = 11 * 60, to = 4 * 60,
                warmupCards = 0,
                kinds = listOf(
                    TaskKind.REPHRASE, TaskKind.ERRORS, TaskKind.BACK_TRANSLATE,
                    TaskKind.CUE_CARD, TaskKind.YESTERDAY, TaskKind.DICTATION, TaskKind.RETELL, TaskKind.DIALOG,
                ).map { it.name },
                escapeMinutes = 30,
            ),
        )

        fun pick(scenarios: List<Scenario>, minuteOfDay: Int): Scenario =
            scenarios.firstOrNull { it.contains(minuteOfDay) } ?: scenarios.firstOrNull() ?: defaults[0]

        /**
         * Chooses the next kind: the one done least recently, ties broken at random.
         * Kinds that cannot run now ([available] is false: no prepared task, no Anki words) are skipped.
         */
        fun chooseKind(
            kinds: List<TaskKind>,
            history: List<String>,
            available: (TaskKind) -> Boolean,
            fallback: TaskKind,
            random: Random = Random.Default,
        ): TaskKind {
            val usable = kinds.filter(available)
            if (usable.isEmpty()) return fallback
            fun lastUse(k: TaskKind) = history.lastIndexOf(k.name)
            val oldest = usable.minOf { lastUse(it) }
            val candidates = usable.filter { lastUse(it) == oldest }
            return candidates[random.nextInt(candidates.size)]
        }

        fun format(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
    }
}
