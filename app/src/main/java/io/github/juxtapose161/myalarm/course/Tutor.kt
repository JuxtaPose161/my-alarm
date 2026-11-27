package io.github.juxtapose161.myalarm.course

import kotlinx.serialization.KSerializer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How hard Claude thinks: low for quick checks during a task, medium for preparing tasks. */
enum class Effort { LOW, MEDIUM, HIGH }

/** One structured request to the model. Implemented with the Anthropic SDK in ClaudeClient. */
interface Llm {
    /** Sends [user] with the tutor system prompt and returns JSON matching [schema]. */
    suspend fun json(user: String, schema: Map<String, Any>, effort: Effort, maxTokens: Long): String
}

/**
 * Everything the app asks Claude: preparing tasks, checking answers, playing roles, guessing words,
 * and keeping the learner profile up to date.
 */
class Tutor(private val llm: Llm, private val store: CourseStore) {

    private suspend fun <T> ask(user: String, schema: Map<String, Any>, serializer: KSerializer<T>, effort: Effort, maxTokens: Long): T {
        val text = llm.json(user, schema, effort, maxTokens)
        return AppJson.decodeFromString(serializer, text)
    }

    /** Prepares one task of [kind]. [words] are candidate Anki words. */
    suspend fun prepare(kind: TaskKind, words: List<String>): PreparedTask {
        val profile = store.profile
        val params = store.params
        val focus = profile.focusGrammar(4)
        val prompt = Prompts.generate(kind, profile, params, words.take(40), focus)
        val now = System.currentTimeMillis()
        return when (kind.engine) {
            Engine.WRITTEN -> PreparedTask(kind.name, profile.level, now,
                written = fixGrammar(ask(prompt, Schemas.written, WrittenSpec.serializer(), Effort.MEDIUM, 8000)))
            Engine.CHAT -> PreparedTask(kind.name, profile.level, now,
                chat = ask(prompt, Schemas.chat, ChatSpec.serializer(), Effort.MEDIUM, 8000).let { c ->
                    c.copy(targetGrammar = c.targetGrammar.filter { Curriculum.grammar(it) != null })
                })
            Engine.DICTATION -> PreparedTask(kind.name, profile.level, now,
                dictation = ask(prompt, Schemas.dictation, DictationSpec.serializer(), Effort.MEDIUM, 8000))
            else -> error("$kind is not prepared in advance")
        }.also {
            // The topic moves on with every prepared task, so tasks prepared in a row differ.
            store.updateProfile { p -> p.copy(topicCursor = p.topicCursor + 1) }
        }
    }

    private fun fixGrammar(s: WrittenSpec) = s.copy(targetGrammar = s.targetGrammar.filter { Curriculum.grammar(it) != null })

    suspend fun evaluate(kind: TaskKind, spec: WrittenSpec, answer: String): Evaluation {
        val profile = store.profile
        return ask(Prompts.evaluate(kind, spec, answer, profile, store.params), Schemas.evaluation,
            Evaluation.serializer(), Effort.LOW, 6000)
    }

    suspend fun chatTurn(kind: TaskKind, spec: ChatSpec, transcript: List<Pair<Boolean, String>>): ChatTurn =
        ask(Prompts.chatTurn(kind, spec, store.profile, transcript), Schemas.chatTurn, ChatTurn.serializer(), Effort.LOW, 3000)

    suspend fun chatEvaluation(kind: TaskKind, spec: ChatSpec, transcript: List<Pair<Boolean, String>>): Evaluation =
        ask(Prompts.chatEvaluation(kind, spec, store.profile, transcript), Schemas.evaluation,
            Evaluation.serializer(), Effort.LOW, 6000)

    suspend fun aliasGuess(explanations: List<String>, wrong: List<String>): AliasGuess =
        ask(Prompts.aliasGuess(store.profile.level, explanations, wrong), Schemas.aliasGuess,
            AliasGuess.serializer(), Effort.LOW, 2000)

    suspend fun synonym(toEnglish: Boolean, prompt: String, cardAnswer: String, answer: String): Verdict =
        ask(Prompts.synonym(toEnglish, prompt, cardAnswer, answer), Schemas.verdict, Verdict.serializer(), Effort.LOW, 1500)

    /** Saves a checked task to the profile and lets Claude rewrite its notes. */
    suspend fun record(kind: TaskKind, title: String, e: Evaluation) {
        store.updateProfile { it.withEvaluation(today(), kind, title, e) }
        val description = buildString {
            append("${kind.name} \"$title\": ${if (e.passed) "passed" else "failed"}. ")
            append(e.criteria.joinToString("; ") { "${it.name}: ${if (it.ok) "ok" else "no"} (${it.comment})" })
            if (e.corrections.isNotEmpty()) append(". Mistakes: " + e.corrections.joinToString("; ") { "${it.wrong} → ${it.right}" })
        }
        val s = runCatching {
            ask(Prompts.summary(store.profile, description), Schemas.profileSummary, ProfileSummary.serializer(), Effort.LOW, 3000)
        }.getOrNull() ?: return
        store.updateProfile { it.copy(summary = s.summary.take(900)) }
    }

    companion object {
        fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }
}
