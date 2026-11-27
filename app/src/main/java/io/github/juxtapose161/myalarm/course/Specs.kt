package io.github.juxtapose161.myalarm.course

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Data exchanged with Claude. Field names match the JSON schemas in Schemas.kt.
 */

/** A task answered in one go (retell, rephrase, errors, back translation, monologues). */
@Serializable
data class WrittenSpec(
    val title: String,
    /** What to do, in Russian. */
    val instructionsRu: String,
    /** Text to read / RU paragraph / text with mistakes / cue card. May be empty. */
    val material: String,
    /** Retell: the text disappears after "I've read it". */
    val hideMaterial: Boolean,
    /** The text can be read aloud by the phone. */
    val speakMaterial: Boolean,
    /** Questions, sentences to rephrase, plan points. */
    val items: List<String>,
    val targetWords: List<String>,
    /** Grammar point ids from the curriculum. */
    val targetGrammar: List<String>,
    /** Seconds to prepare before writing (cue card); 0 = none. */
    val prepSeconds: Int,
    /** Minimum length of the answer in words; 0 = any. */
    val minWords: Int,
)

/** A role-play. */
@Serializable
data class ChatSpec(
    val title: String,
    /** The situation, in Russian. */
    val situationRu: String,
    /** Who Claude plays, in English (for the model). */
    val aiRole: String,
    /** The user's goal, in Russian. */
    val userGoalRu: String,
    /** The first line, said by Claude's character. */
    val opening: String,
    val targetWords: List<String>,
    val targetGrammar: List<String>,
)

/** One reply in a role-play plus what the user's last message used. */
@Serializable
data class ChatTurn(
    val reply: String,
    val usedWords: List<String>,
    /** Grammar ids used correctly in the user's last message. */
    val usedGrammar: List<String>,
    val goalReached: Boolean,
    /** Short correction of the user's last message, in Russian; empty if fine. */
    val correction: String,
)

/** Text read aloud; gaps are marked as [[word]]. */
@Serializable
data class DictationSpec(
    val title: String,
    val text: String,
)

@Serializable
data class Criterion(val name: String, val ok: Boolean, val comment: String)

@Serializable
data class Correction(val wrong: String, val right: String, val why: String)

/** Claude's verdict on an answer, plus notes for the learner profile. */
@Serializable
data class Evaluation(
    val criteria: List<Criterion>,
    val corrections: List<Correction>,
    /** Short feedback in Russian. */
    val feedbackRu: String,
    val usedWords: List<String>,
    /** Grammar ids used correctly. */
    val grammarOk: List<String>,
    /** Grammar ids used with mistakes. */
    val grammarErrors: List<String>,
    val strengths: List<String>,
    val weaknesses: List<String>,
) {
    val passed get() = criteria.isNotEmpty() && criteria.all { it.ok }
}

/** Claude's guess in the "explain the word" game. */
@Serializable
data class AliasGuess(val guess: String, val alternatives: List<String>)

/** Is a translation acceptable (synonym check in task 1). */
@Serializable
data class Verdict(val acceptable: Boolean, val comment: String)

/** A new summary of the learner after a task. */
@Serializable
data class ProfileSummary(val summary: String)

/** A prepared task as stored in the cache. */
@Serializable
data class PreparedTask(
    val kind: String,
    val level: String,
    val createdAt: Long,
    val written: WrittenSpec? = null,
    val chat: ChatSpec? = null,
    val dictation: DictationSpec? = null,
)

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
}
