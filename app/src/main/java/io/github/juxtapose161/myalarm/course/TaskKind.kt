package io.github.juxtapose161.myalarm.course

import kotlinx.serialization.Serializable

/** How a kind of task is run on the phone. */
enum class Engine {
    /** The old quick cards (choice / audio / cloze / typing / math). */
    QUICK,
    /** Translate N words from Anki. */
    TRANSLATE,
    /** A task prepared by Claude, answered in text fields and checked on the phone by its criteria. */
    PREPARED,
    /** Listen and fill the gaps (prepared by Claude). */
    DICTATION,
}

/** How the answer of a prepared task is entered. */
enum class AnswerMode {
    /** One text for the whole task. */
    SINGLE,
    /** One text per item (rephrasings). */
    PER_ITEM,
    /** A main text plus one text per item (retell + questions). */
    MAIN_AND_ITEMS,
    /** Items are the partner's lines, answered one after another like a chat. */
    DIALOG,
}

enum class TaskKind(
    val title: String,
    val description: String,
    val engine: Engine,
    val mode: AnswerMode = AnswerMode.SINGLE,
) {
    QUICK("Быстрые карточки", "Выбор перевода, аудио, пропуск, набор слова, примеры", Engine.QUICK),
    TRANSLATE("Перевод слов", "N слов, RU→EN и EN→RU по очереди. Ошибка — карточка пропускается", Engine.TRANSLATE),
    DIALOG("Диалог", "Ситуация и реплики собеседника (дорога, рецепт, собеседование…) — отвечать по очереди, используя слова из Anki и грамматику", Engine.PREPARED, AnswerMode.DIALOG),
    RETELL("Пересказ", "Прочитать или прослушать текст, пересказать и ответить на вопросы", Engine.PREPARED, AnswerMode.MAIN_AND_ITEMS),
    REPHRASE("Скажи иначе", "Переформулировать фразу заданными конструкциями", Engine.PREPARED, AnswerMode.PER_ITEM),
    ERRORS("Найди ошибки", "Текст с ошибками под твои слабые места — исправить", Engine.PREPARED),
    BACK_TRANSLATE("Обратный перевод", "Абзац на русском → по-английски, со словами и грамматикой урока", Engine.PREPARED),
    CUE_CARD("Монолог по карточке", "Тема и план как в IELTS Speaking Part 2: подготовка и монолог", Engine.PREPARED),
    DAY_PLAN("План дня", "Рассказать, что предстоит сегодня, будущими временами", Engine.PREPARED),
    YESTERDAY("Вчерашний день", "Рассказ о вчерашнем дне с прошедшими временами", Engine.PREPARED),
    DICTATION("Диктант с пропусками", "Телефон читает текст, нужно вписать пропущенные слова", Engine.DICTATION),
    ;

    /** Prepared by Claude in advance (needs a synced task). */
    val prepared get() = engine == Engine.PREPARED || engine == Engine.DICTATION

    companion object {
        fun byName(name: String): TaskKind? = entries.firstOrNull { it.name == name }
    }
}

/** Numbers that set the size of each task (the "N" and "M" of the task descriptions). */
@Serializable
data class TaskParams(
    val translateWords: Int = 5,
    val dialogMinutes: Int = 5,
    val dialogTurns: Int = 5,
    val dialogWords: Int = 4,
    val dialogGrammar: Int = 2,
    val retellQuestions: Int = 3,
    val retellWords: Int = 3,
    val rephraseCount: Int = 3,
    val errorsCount: Int = 5,
    val backTranslateWords: Int = 3,
    val backTranslateGrammar: Int = 2,
    val cueCardMinWords: Int = 120,
    val storyGrammar: Int = 3,
    val dictationGaps: Int = 8,
)
