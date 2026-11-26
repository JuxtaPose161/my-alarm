package io.github.juxtapose161.myalarm.quiz

/** One vocabulary item taken from an Anki note. */
data class Word(
    val noteId: Long,
    /** English side, already cleaned of HTML. */
    val en: String,
    /** Russian side (translation), already cleaned of HTML. */
    val ru: String,
    /** Optional English example sentence. */
    val example: String? = null,
) {
    val variants: List<String> by lazy { TextUtil.answerVariants(en) }
    val main: String get() = variants.firstOrNull() ?: en
}

enum class TaskType(val title: String) {
    CHOICE("Выбор перевода"),
    AUDIO("Аудио"),
    CLOZE("Пропуск в предложении"),
    TYPING("Набрать слово"),
    MATH("Математика"),
}

sealed class QuizTask {
    abstract val type: TaskType

    /** Pick one of [options]. [speak] is text to read aloud (audio task). */
    data class Choice(
        override val type: TaskType,
        val instruction: String,
        val prompt: String,
        val options: List<String>,
        val correctIndex: Int,
        val word: Word,
        val speak: String? = null,
    ) : QuizTask()

    /** Type the English word for [prompt]. */
    data class Typing(
        val prompt: String,
        val word: Word,
    ) : QuizTask() {
        override val type = TaskType.TYPING
    }

    data class Math(
        val expression: String,
        val answer: Long,
    ) : QuizTask() {
        override val type = TaskType.MATH
    }
}
