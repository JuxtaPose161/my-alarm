package io.github.juxtapose161.myalarm.ui

import io.github.juxtapose161.myalarm.course.ChatSpec
import io.github.juxtapose161.myalarm.course.Dictation
import io.github.juxtapose161.myalarm.course.TaskKind
import io.github.juxtapose161.myalarm.course.WrittenSpec
import io.github.juxtapose161.myalarm.quiz.QuizSession
import io.github.juxtapose161.myalarm.quiz.TranslateSession
import io.github.juxtapose161.myalarm.quiz.Word

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

    data class Written(override val kind: TaskKind, val spec: WrittenSpec) : Step() {
        override val title = spec.title
    }

    data class Chat(override val kind: TaskKind, val spec: ChatSpec, val minutes: Int, val words: Int, val grammar: Int) : Step() {
        override val title = spec.title
    }

    data class Alias(val words: List<Word>, val required: Int) : Step() {
        override val kind = TaskKind.ALIAS
        override val title = kind.title
    }

    data class Dictate(val dictation: Dictation) : Step() {
        override val kind = TaskKind.DICTATION
        override val title = dictation.title
    }
}

/** What a step reports when it ends. */
data class StepResult(
    val passed: Boolean,
    /** Anki note ids answered wrongly / correctly (word tasks). */
    val missedNotes: Set<Long> = emptySet(),
    val correctNotes: Set<Long> = emptySet(),
    val missedWords: List<String> = emptyList(),
)
