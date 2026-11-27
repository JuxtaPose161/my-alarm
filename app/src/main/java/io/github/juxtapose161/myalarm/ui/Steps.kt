package io.github.juxtapose161.myalarm.ui

import io.github.juxtapose161.myalarm.course.AnswerFile
import io.github.juxtapose161.myalarm.course.Dictation
import io.github.juxtapose161.myalarm.course.TaskFile
import io.github.juxtapose161.myalarm.course.TaskKind
import io.github.juxtapose161.myalarm.quiz.QuizSession
import io.github.juxtapose161.myalarm.quiz.TranslateSession

/** One part of a captcha run (warm-up cards, then the main task). */
sealed class Step {
    abstract val kind: TaskKind
    abstract val title: String

    data class Quick(val session: QuizSession, val warmup: Boolean) : Step() {
        override val kind = TaskKind.QUICK
        override val title = if (warmup) "Разминка" else TaskKind.QUICK.title
    }

    data class Translate(val session: TranslateSession) : Step() {
        override val kind = TaskKind.TRANSLATE
        override val title = kind.title
    }

    /** A task prepared by Claude and checked on the phone by its criteria. */
    data class Prepared(override val kind: TaskKind, val task: TaskFile) : Step() {
        override val title = task.title
    }

    data class Dictate(val task: TaskFile, val dictation: Dictation) : Step() {
        override val kind = TaskKind.DICTATION
        override val title = task.title
    }
}

/** What a step reports when it ends. */
data class StepResult(
    val passed: Boolean,
    /** Anki note ids answered wrongly / correctly (word tasks). */
    val missedNotes: Set<Long> = emptySet(),
    val correctNotes: Set<Long> = emptySet(),
    /** The answer to upload for Claude's review (filled in by the step; ids and times by the host). */
    val answer: AnswerFile? = null,
)
