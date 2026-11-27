package io.github.juxtapose161.myalarm.course

import kotlinx.serialization.Serializable

/** How a kind of task is run. */
enum class Engine {
    /** The old quick cards (choice / audio / cloze / typing / math). Offline. */
    QUICK,
    /** Translate N words, checked locally. Offline. */
    TRANSLATE,
    /** A written / dictated answer to a generated task, checked by Claude. */
    WRITTEN,
    /** A conversation with Claude playing a role. */
    CHAT,
    /** Explain a word, Claude guesses it. */
    ALIAS,
    /** Listen and fill the gaps, checked locally. */
    DICTATION,
}

enum class TaskKind(val title: String, val description: String, val engine: Engine) {
    QUICK("Быстрые карточки", "Выбор перевода, аудио, пропуск, набор слова, примеры", Engine.QUICK),
    TRANSLATE("Перевод слов", "N слов, RU→EN и EN→RU по очереди. Ошибка — карточка пропускается", Engine.TRANSLATE),
    DIALOG("Диалог", "Ситуация и первая фраза, нужно продолжить разговор, используя слова из Anki и грамматику", Engine.CHAT),
    NEGOTIATION("Переговоры с целью", "Упрямый собеседник, нужно добиться своего", Engine.CHAT),
    RETELL("Пересказ", "Прочитать или прослушать текст, пересказать и ответить на вопросы", Engine.WRITTEN),
    ALIAS("Объясни слово", "Объяснить слово из Anki, не называя его. Claude угадывает", Engine.ALIAS),
    REPHRASE("Скажи иначе", "Переформулировать фразу заданными конструкциями", Engine.WRITTEN),
    ERRORS("Найди ошибки", "Текст с ошибками под твои слабые места — исправить", Engine.WRITTEN),
    BACK_TRANSLATE("Обратный перевод", "Абзац на русском → по-английски, со словами и грамматикой урока", Engine.WRITTEN),
    CUE_CARD("Монолог по карточке", "Тема и план как в IELTS Speaking Part 2: подготовка и монолог", Engine.WRITTEN),
    DAY_PLAN("План дня", "Рассказать, что предстоит сегодня, будущими временами", Engine.WRITTEN),
    YESTERDAY("Вчерашний день", "Рассказ о вчерашнем дне с прошедшими временами", Engine.WRITTEN),
    DICTATION("Диктант с пропусками", "Телефон читает текст, нужно вписать пропущенные слова", Engine.DICTATION),
    ;

    /** Needs Claude (a key and, at the moment of the task or before it, the network). */
    val needsClaude get() = engine !in setOf(Engine.QUICK, Engine.TRANSLATE)

    /** Can be prepared in advance (evening before), so the morning only needs the check. */
    val pregenerated get() = engine in setOf(Engine.WRITTEN, Engine.CHAT, Engine.DICTATION)

    companion object {
        fun byName(name: String): TaskKind? = entries.firstOrNull { it.name == name }
    }
}

/** Numbers that set the size of each task (the "N" and "M" of the task descriptions). */
@Serializable
data class TaskParams(
    val translateWords: Int = 5,
    val dialogMinutes: Int = 5,
    val dialogWords: Int = 4,
    val dialogGrammar: Int = 2,
    val retellQuestions: Int = 3,
    val retellWords: Int = 3,
    val aliasWords: Int = 3,
    val rephraseCount: Int = 3,
    val errorsCount: Int = 5,
    val backTranslateWords: Int = 3,
    val backTranslateGrammar: Int = 2,
    val cueCardMinWords: Int = 120,
    val storyGrammar: Int = 3,
    val dictationGaps: Int = 8,
)
