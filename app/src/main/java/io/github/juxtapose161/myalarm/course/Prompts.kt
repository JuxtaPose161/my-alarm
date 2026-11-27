package io.github.juxtapose161.myalarm.course

/**
 * Prompts for Claude. The system prompt is the same for every request; everything that changes
 * (profile, words, the task) goes into the user message.
 */
object Prompts {

    val system = """
        You are an English tutor inside an alarm-clock app. The learner is a native Russian speaker.
        To switch off an alarm or a scheduled reminder the learner must complete a short English task
        that you prepare or check. The tasks follow a course built on English File (4th edition):
        grammar and topics of the learner's level, with review of earlier levels.

        Principles:
        - Match the learner's CEFR level exactly. Texts and replies use vocabulary of that level plus the target words.
        - Tasks are short enough for 3-7 minutes, often right after waking up. Make them concrete and lively,
          about everyday situations, work, travel, the learner's life. No childish content.
        - Target words come from the learner's Anki deck. Build the task so that the words are naturally needed.
        - Grammar is referred to by the ids given in the request. Only use those ids in id fields.
        - Instructions and feedback for the learner are in Russian; everything the learner reads or answers
          in English is in English.
        - The learner may answer by voice typing, so ignore missing punctuation and capital letters,
          and treat obvious speech-to-text slips (homophones, a missing article caused by dictation) leniently.
          Real grammar and word-choice mistakes still count.
        - Be strict but fair when checking: a criterion is met only if the answer really meets it.
          A word counts as used if any form of it is used correctly and with its meaning (run/ran/running).
        - Keep feedback short: at most 3 sentences plus the list of corrections.
    """.trimIndent()

    private fun grammarLines(grammar: List<Curriculum.Grammar>) =
        grammar.joinToString("\n") { "- ${it.id}: ${it.name} (${it.ru})" }

    /** Request to prepare one task. */
    fun generate(
        kind: TaskKind,
        profile: LearnerProfile,
        params: TaskParams,
        words: List<String>,
        focus: List<Curriculum.Grammar>,
    ): String = buildString {
        appendLine("Learner profile:")
        appendLine(profile.describe())
        appendLine()
        appendLine("Topic of the day (you may adapt it): ${profile.topic()}")
        appendLine()
        appendLine("Grammar to practise (ids):")
        appendLine(grammarLines(focus))
        appendLine()
        appendLine("Candidate words from the learner's Anki deck (choose the ones that fit one topic):")
        appendLine(words.joinToString(", "))
        appendLine()
        appendLine("Prepare this task:")
        appendLine(taskBrief(kind, params))
    }

    private fun taskBrief(kind: TaskKind, p: TaskParams): String = when (kind) {
        TaskKind.DIALOG -> """
            DIALOG. A role-play that lasts about ${p.dialogMinutes} minutes of chat. Give a situation where the
            learner has to do something real in English: give directions, dictate a recipe, answer a job
            interviewer, explain a problem to a support agent, plan a trip with a friend, etc.
            aiRole: who you will play (in English, with personality). opening: the first line of your character,
            which clearly asks the learner something. userGoalRu: what the learner must achieve, in Russian.
            situationRu: 1-2 sentences in Russian. targetWords: exactly ${p.dialogWords} words from the candidates
            that fit the situation. targetGrammar: exactly ${p.dialogGrammar} ids from the grammar list that the
            situation naturally invites.
        """.trimIndent()
        TaskKind.NEGOTIATION -> """
            NEGOTIATION. A role-play with a conflict where the learner must get something from a reluctant person:
            return an item without a receipt, move a deadline, get a discount, refuse an upsell, resolve a
            neighbour dispute. aiRole: the reluctant person, who gives in only to polite, clear, well-argued
            English (with a compromise along the way). opening: the character's first line. userGoalRu: the exact
            goal in Russian. situationRu: 1-2 sentences in Russian. targetWords: 2-3 fitting candidate words.
            targetGrammar: 1-2 ids (polite requests, conditionals and modals fit well).
        """.trimIndent()
        TaskKind.RETELL -> """
            RETELL. material: an English text of ${retellLength()} at the learner's level on the topic, interesting
            (a short news story, an anecdote, a story from someone's life, a popular-science fact), that uses
            ${p.retellWords} candidate words. hideMaterial: true (the text is hidden while the learner retells).
            speakMaterial: true. items: exactly ${p.retellQuestions} open questions about the text in English that
            need more than one word to answer (why/how/what would). targetWords: the ${p.retellWords} words
            the learner must use in the retelling and answers. targetGrammar: 1-2 ids that fit.
            instructionsRu: say that one must retell the gist in their own words, answer the questions and use the
            words. minWords: about 60 for A1-A2, 90 for B1-B2, 120 for C1-C2. prepSeconds: 0.
        """.trimIndent()
        TaskKind.REPHRASE -> """
            REPHRASE ("say it differently"). material: one natural English sentence (or two short ones) on the topic.
            items: exactly ${p.rephraseCount} instructions in Russian, each naming a different construction to rewrite
            the sentence with, keeping the meaning (for example "страдательный залог", "условное 2-го типа",
            "косвенная речь", "инверсия"), chosen from the grammar list. targetGrammar: the ids of those constructions.
            targetWords: []. hideMaterial: false. speakMaterial: true. minWords: 0. prepSeconds: 0.
        """.trimIndent()
        TaskKind.ERRORS -> """
            ERRORS ("find the mistakes"). material: an English text of 6-9 sentences on the topic with exactly
            ${p.errorsCount} mistakes typical for Russian speakers and for this learner's weak points (articles,
            tenses, prepositions, word order, the grammar list, word choice of candidate words).
            Each mistake is in a different sentence, the rest of the text is correct. Do not mark the mistakes.
            items: []. targetGrammar: ids of the grammar the mistakes test. targetWords: candidate words used in the text.
            instructionsRu: ask to write out each wrong fragment and its correction ("wrong → right"),
            without the reasons. hideMaterial: false. speakMaterial: false. minWords: 0. prepSeconds: 0.
            In instructionsRu do not say how many mistakes there are.
        """.trimIndent()
        TaskKind.BACK_TRANSLATE -> """
            BACK_TRANSLATE. material: a natural paragraph in RUSSIAN (4-6 sentences) on the topic whose good English
            translation needs ${p.backTranslateWords} candidate words and ${p.backTranslateGrammar} constructions from
            the grammar list. targetWords and targetGrammar: those. items: []. hideMaterial: false.
            speakMaterial: false. instructionsRu: translate into English, the meaning matters, not word-for-word.
            minWords: 0. prepSeconds: 0.
        """.trimIndent()
        TaskKind.CUE_CARD -> """
            CUE_CARD (IELTS Speaking Part 2 style). material: the card: "Describe …" plus "You should say:" and
            exactly 3 bullet points, in English. items: the 3 points. targetWords: 2-3 fitting candidate words.
            targetGrammar: 1-2 ids. prepSeconds: 60. minWords: ${p.cueCardMinWords}. instructionsRu: a minute to
            prepare, then a monologue covering every point, with linking words. hideMaterial: false. speakMaterial: true.
        """.trimIndent()
        TaskKind.DAY_PLAN -> """
            DAY_PLAN. The learner tells their real plan for today. material: a short English prompt question.
            items: 3-4 English sub-questions (main task, meetings, after work, one thing they are looking forward to).
            targetGrammar: ${p.storyGrammar} ids of future forms available at or below the level
            (going to, Present Continuous for arrangements, will, Future Continuous, Future Perfect, was going to…).
            If a needed future-form id is missing from the grammar list, use the closest one from the list.
            targetWords: 1-2 candidate words that could fit a day plan, or [] if none fits.
            minWords: 50 for A1-A2, 80 for B1-B2, 110 for C1-C2. hideMaterial: false. speakMaterial: false. prepSeconds: 0.
        """.trimIndent()
        TaskKind.YESTERDAY -> """
            YESTERDAY. The learner tells about yesterday (real or invented, but coherent). material: a short English
            prompt with an angle (something unexpected, a decision, a conversation). items: 3 English sub-questions.
            targetGrammar: ${p.storyGrammar} ids of past/narrative forms at or below the level (past simple,
            past continuous, past perfect, used to, narrative tenses, modals of deduction…).
            targetWords: 1-3 candidate words that could fit. minWords: like DAY_PLAN.
            hideMaterial: false. speakMaterial: false. prepSeconds: 0.
        """.trimIndent()
        TaskKind.DICTATION -> """
            DICTATION. title and text: an English text of 5-7 sentences at the learner's level on the topic.
            Mark exactly ${p.dictationGaps} gaps as [[word]] — single words (no spaces inside the brackets),
            mostly candidate words, plus words that are hard to hear (articles in fast speech, endings -ed/-s,
            prepositions). Never put two gaps next to each other.
        """.trimIndent()
        else -> error("$kind is not generated")
    }

    private fun retellLength() = "120-200 words (shorter for A1-A2, longer for C1-C2)"

    /** Request to check a written answer. */
    fun evaluate(kind: TaskKind, spec: WrittenSpec, answer: String, profile: LearnerProfile, params: TaskParams): String =
        buildString {
            appendLine("Learner level: ${profile.level}.")
            appendLine()
            appendLine("Task (${kind.name}): ${spec.title}")
            appendLine("Instructions given to the learner: ${spec.instructionsRu}")
            if (spec.material.isNotBlank()) appendLine("Material:\n${spec.material}")
            if (spec.items.isNotEmpty()) appendLine("Items:\n" + spec.items.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n"))
            if (spec.targetWords.isNotEmpty()) appendLine("Target words: ${spec.targetWords.joinToString()}")
            if (spec.targetGrammar.isNotEmpty()) {
                appendLine("Target grammar:")
                appendLine(grammarLines(spec.targetGrammar.mapNotNull { Curriculum.grammar(it) }))
            }
            if (spec.minWords > 0) appendLine("Minimum length: ${spec.minWords} words.")
            appendLine()
            appendLine("Learner's answer:")
            appendLine("<<<")
            appendLine(answer.trim())
            appendLine(">>>")
            appendLine()
            appendLine("Check it. criteria (names in Russian, comment in Russian, one per line):")
            appendLine(criteria(kind, spec, params))
            appendLine(
                """
                Also: corrections — real mistakes in the answer (wrong fragment, right version, a few words why, in Russian),
                at most 6, most important first; feedbackRu — 1-3 sentences; usedWords — target words used correctly;
                grammarOk / grammarErrors — ids of the curriculum grammar (any level) used correctly / with mistakes;
                strengths / weaknesses — 0-2 short notes each, in Russian, about the learner's English, for the tutor's memory.
                """.trimIndent()
            )
        }

    private fun criteria(kind: TaskKind, s: WrittenSpec, p: TaskParams): String = when (kind) {
        TaskKind.RETELL -> """
            - "Суть пересказана": the main points of the text are retold in the learner's own words, not copied.
            - "Ответы на вопросы": all ${s.items.size} questions are answered correctly according to the text.
            - "Слова из Anki": at least ${minOf(p.retellWords, s.targetWords.size)} target words are used correctly.
            - "Объём": at least ${s.minWords} words.
        """.trimIndent()
        TaskKind.REPHRASE -> """
            - one criterion per item, named after the construction: the rewrite uses it correctly and keeps the meaning.
        """.trimIndent()
        TaskKind.ERRORS -> """
            - "Найдены ошибки": at least ${(p.errorsCount - 1).coerceAtLeast(1)} of the ${p.errorsCount} planted mistakes are found and corrected properly.
            - "Без ложных исправлений": the learner did not "correct" correct fragments into wrong ones (one harmless style change is fine).
            In feedbackRu list the planted mistakes the learner missed.
        """.trimIndent()
        TaskKind.BACK_TRANSLATE -> """
            - "Смысл передан": the meaning of every sentence is conveyed (style may differ).
            - "Слова": at least ${minOf(p.backTranslateWords, s.targetWords.size)} target words used correctly.
            - "Грамматика урока": at least ${minOf(p.backTranslateGrammar, s.targetGrammar.size)} target constructions used correctly.
            - "Без грубых ошибок": no mistakes that change the meaning or make a sentence ungrammatical at this level (at most 2 small slips).
        """.trimIndent()
        TaskKind.CUE_CARD -> """
            - "Все пункты": every point of the card is covered.
            - "Связность": linking words and a clear structure (beginning, development, ending).
            - "Объём": at least ${s.minWords} words.
            - "Грамматика урока": at least one target construction used correctly.
        """.trimIndent()
        TaskKind.DAY_PLAN, TaskKind.YESTERDAY -> """
            - "Ответ на вопросы": the sub-questions are covered.
            - "Времена": at least ${minOf(p.storyGrammar, s.targetGrammar.size)} of the target constructions used correctly and where they fit.
            - "Объём": at least ${s.minWords} words.
            - "Без грубых ошибок": at most 2 small slips at this level.
        """.trimIndent()
        else -> "- \"Задание выполнено\": the task is done as instructed."
    }

    /** Request for the next line of a role-play. */
    fun chatTurn(kind: TaskKind, spec: ChatSpec, profile: LearnerProfile, transcript: List<Pair<Boolean, String>>): String =
        buildString {
            appendLine("You are playing a role in a ${if (kind == TaskKind.NEGOTIATION) "negotiation" else "role-play"} with the learner (level ${profile.level}).")
            appendLine("Your role: ${spec.aiRole}")
            appendLine("Situation (as told to the learner): ${spec.situationRu}")
            appendLine("Learner's goal (as told to the learner): ${spec.userGoalRu}")
            appendLine("Target words: ${spec.targetWords.joinToString()}")
            appendLine("Target grammar:")
            appendLine(grammarLines(spec.targetGrammar.mapNotNull { Curriculum.grammar(it) }))
            appendLine()
            appendLine("Conversation so far (AI = your character, L = learner):")
            transcript.forEach { (isAi, text) -> appendLine("${if (isAi) "AI" else "L"}: $text") }
            appendLine()
            appendLine(
                """
                Write your character's next line (reply): 1-3 sentences at the learner's level, staying in role,
                and keep the conversation going with a question or a new turn of the situation so that the learner
                has chances to use the target words and grammar. Never mention the words, grammar or the task.
                ${if (kind == TaskKind.NEGOTIATION) "Give in only step by step and only to polite, clear arguments; offer a compromise before giving in fully." else ""}
                Then analyse ONLY the learner's last message: usedWords — target words used correctly there;
                usedGrammar — target grammar ids used correctly there; correction — if the last message has a real
                mistake, a short correction in Russian ("X → Y"), otherwise "".
                goalReached — true only when the learner's goal is clearly achieved in the conversation.
                """.trimIndent()
            )
        }

    /** Request for the final check of a role-play. */
    fun chatEvaluation(kind: TaskKind, spec: ChatSpec, profile: LearnerProfile, transcript: List<Pair<Boolean, String>>): String =
        buildString {
            appendLine("Learner level: ${profile.level}. Role-play (${kind.name}): ${spec.title}")
            appendLine("Learner's goal: ${spec.userGoalRu}")
            appendLine("Target words: ${spec.targetWords.joinToString()}")
            appendLine("Target grammar:")
            appendLine(grammarLines(spec.targetGrammar.mapNotNull { Curriculum.grammar(it) }))
            appendLine()
            appendLine("Conversation (AI = tutor's character, L = learner):")
            transcript.forEach { (isAi, text) -> appendLine("${if (isAi) "AI" else "L"}: $text") }
            appendLine()
            appendLine(
                """
                The pass/fail decision is made by the app; here give the debrief. criteria: one item "Цель" (goal achieved?)
                and one item "Уместность" (the learner's lines fit the situation and react to the partner). corrections — the
                most important real mistakes of the learner (at most 6). feedbackRu — 1-3 sentences. usedWords,
                grammarOk, grammarErrors, strengths, weaknesses as usual.
                """.trimIndent()
            )
        }

    /** Request for a guess in the "explain the word" game. */
    fun aliasGuess(level: String, explanations: List<String>, wrongGuesses: List<String>): String = buildString {
        appendLine("Word-guessing game (like Alias / Taboo). The learner (level $level) explains one English word or phrase without naming it.")
        appendLine("Explanation so far:")
        explanations.forEach { appendLine("- $it") }
        if (wrongGuesses.isNotEmpty()) appendLine("Your wrong guesses so far: ${wrongGuesses.joinToString()}")
        appendLine(
            "Guess the word. guess: your single best guess (the dictionary form, lowercase). " +
                "alternatives: 0-3 other likely words. Do not repeat wrong guesses."
        )
    }

    /** Request to accept a translation that is not on the card (synonym check). */
    fun synonym(toEnglish: Boolean, prompt: String, cardAnswer: String, answer: String): String =
        "Vocabulary card. The learner had to translate " +
            (if (toEnglish) "the Russian \"$prompt\" into English" else "the English \"$prompt\" into Russian") +
            ". The card's answer: \"$cardAnswer\". The learner answered: \"$answer\" (possibly by voice typing).\n" +
            "acceptable: true only if the answer is a correct translation with the same meaning in common use " +
            "(a synonym or another correct form), with no spelling mistake that changes the word. " +
            "comment: a few words in Russian."

    /** Request to rewrite the tutor's notes. */
    fun summary(profile: LearnerProfile, lastTask: String): String = buildString {
        appendLine("Current notes about the learner (may be empty):")
        appendLine(profile.summary.ifBlank { "(none)" })
        appendLine()
        appendLine("Profile data:")
        appendLine(profile.describe())
        appendLine()
        appendLine("The latest task and its result:")
        appendLine(lastTask)
        appendLine()
        appendLine(
            "Rewrite the notes (summary) in Russian, at most 600 characters: what the learner already does well, " +
                "the 2-3 most important things to work on next and any pattern in their mistakes. " +
                "This is the tutor's memory used to plan the next tasks."
        )
    }
}
