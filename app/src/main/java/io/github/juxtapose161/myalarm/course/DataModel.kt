package io.github.juxtapose161.myalarm.course

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Files in the private data repository, shared by the phone and the scheduled Claude Code session.
 * The format is described for Claude in tutor/TUTOR.md and checked by tutor/validate.py;
 * keep the three in sync.
 */

/** A target word and the forms that count as using it. */
@Serializable
data class WordCheck(val word: String, val forms: List<String> = emptyList())

/** A grammar construction, recognised on the phone by a regular expression. */
@Serializable
data class GrammarCheck(
    /** Curriculum id. */
    val id: String,
    /** Java regex, matched case-insensitively against the lowercased answer. */
    val pattern: String,
    /** Short Russian name shown to the learner. */
    val hintRu: String = "",
    /** Sentences that must match the pattern (checked by validate.py). */
    val examples: List<String> = emptyList(),
)

/** A key point: counted when any of the alternatives appears in the answer. */
@Serializable
data class KeyPoint(val nameRu: String, val anyOf: List<String>)

/** A planted mistake in the "find the mistakes" text. */
@Serializable
data class PlantedError(val wrong: String, val right: List<String>, val whyRu: String = "")

/** One question / sentence to rephrase / partner line / plan point. */
@Serializable
data class TaskItem(
    val text: String,
    /** Key points the answer to this item must contain ([needPoints] of them). */
    val points: List<KeyPoint> = emptyList(),
    val needPoints: Int = 0,
    /** A construction the answer to this item must use (rephrase). */
    val grammar: GrammarCheck? = null,
    val minWords: Int = 0,
)

/** A task prepared by Claude. */
@Serializable
data class TaskFile(
    val id: String,
    val kind: String,
    val level: String,
    val createdAt: String = "",
    val title: String,
    /** What to do, in Russian. */
    val instructionsRu: String,
    /** Text to read / RU paragraph / text with mistakes / cue card / situation. */
    val material: String = "",
    val hideMaterial: Boolean = false,
    val speakMaterial: Boolean = false,
    val prepSeconds: Int = 0,
    val minWords: Int = 0,
    val items: List<TaskItem> = emptyList(),
    val words: List<WordCheck> = emptyList(),
    val needWords: Int = 0,
    val grammar: List<GrammarCheck> = emptyList(),
    val needGrammar: Int = 0,
    /** Key points of the whole answer (retell gist, meaning of a translation, plan points). */
    val points: List<KeyPoint> = emptyList(),
    val needPoints: Int = 0,
    val errors: List<PlantedError> = emptyList(),
    val needErrors: Int = 0,
    /** Linking words required in the whole answer (cue card). */
    val needLinkers: Int = 0,
    /** Dialog: minimum minutes before the dialog can be finished. */
    val minMinutes: Int = 0,
    /** Dictation text with [[gaps]]. */
    val dictation: String = "",
) {
    val taskKind: TaskKind? get() = TaskKind.byName(kind)
}

/** What the phone uploads after a task. */
@Serializable
data class AnswerFile(
    val taskId: String,
    val kind: String,
    val title: String = "",
    val answeredAt: String,
    val scenario: String = "",
    /** The main text (single answer). */
    val main: String = "",
    /** Answers per item. */
    val items: List<String> = emptyList(),
    /** The phone's verdict by the task's criteria. */
    val criteria: List<Criterion> = emptyList(),
    val passed: Boolean,
    val attempts: Int = 1,
    /** Seconds spent. */
    val seconds: Long = 0,
    /** Words: notes answered wrongly / correctly (translate, quick cards, dictation gaps). */
    val missedWords: List<String> = emptyList(),
    val correctWords: List<String> = emptyList(),
    /** Translate answers not on the card, for Claude to judge later. */
    val disputed: List<String> = emptyList(),
    val preview: Boolean = false,
)

@Serializable
data class Criterion(val name: String, val ok: Boolean, val comment: String = "")

@Serializable
data class Correction(val wrong: String, val right: String, val why: String = "")

/** Claude's review of an answer, written by the scheduled session. */
@Serializable
data class ReviewFile(
    val taskId: String,
    val kind: String = "",
    val title: String = "",
    val reviewedAt: String = "",
    /** Claude's own verdict (may differ from the phone's). */
    val passed: Boolean = true,
    val criteria: List<Criterion> = emptyList(),
    val corrections: List<Correction> = emptyList(),
    val feedbackRu: String = "",
    /** A better version of the answer, if useful. */
    val modelAnswer: String = "",
)

/** What the phone publishes for the session: settings and candidate Anki words. */
@Serializable
data class PhoneState(
    val updatedAt: String,
    val level: String,
    val params: TaskParams,
    /** Kinds used by the scenarios (the session prepares only these). */
    val kinds: List<String>,
    /** Prepared tasks to keep per kind. */
    val stock: Int,
    val words: List<PhoneWord>,
)

@Serializable
/** In priority order: last mistakes, cards due in Anki today, then random ones. */
data class PhoneWord(val en: String, val ru: String, val example: String = "", val missed: Boolean = false)

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
    prettyPrint = true
}
