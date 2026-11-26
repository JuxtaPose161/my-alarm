package io.github.juxtapose161.myalarm.quiz

/**
 * The rules of one wake-up session.
 *
 * - The user needs [required] correct answers.
 * - A wrong word answer adds one more question (at most [MAX_PENALTY] in total)
 *   and the word comes back later as a typing task.
 * - Typing: two attempts, then the answer is shown and it counts as a mistake.
 * - Math: retry until correct, like in Alarmy.
 */
class QuizSession(
    private val generator: TaskGenerator,
    private val baseRequired: Int,
) {
    var required = baseRequired; private set
    var done = 0; private set
    var current: QuizTask = generator.next(); private set

    private var typingAttempts = 0
    private val retryQueue = ArrayDeque<Word>()
    private var sinceRetry = 0

    /** Notes answered wrongly / correctly in this session. */
    val missed = linkedSetOf<Long>()
    val correct = linkedSetOf<Long>()

    val isFinished get() = done >= required

    sealed class Result {
        data class ChoiceResult(val correct: Boolean, val correctIndex: Int) : Result()
        object TypingCorrect : Result()
        data class TypingWrong(val hint: String) : Result()
        data class TypingRevealed(val answer: String) : Result()
        object MathCorrect : Result()
        object MathWrong : Result()
    }

    fun answerChoice(index: Int): Result.ChoiceResult {
        val task = current as QuizTask.Choice
        val ok = index == task.correctIndex
        if (ok) success(task.word) else mistake(task.word)
        return Result.ChoiceResult(ok, task.correctIndex)
    }

    fun answerTyping(input: String): Result {
        val task = current as QuizTask.Typing
        if (TextUtil.isTypedAnswerCorrect(input, task.word.variants)) {
            success(task.word)
            return Result.TypingCorrect
        }
        typingAttempts++
        return if (typingAttempts >= 2) {
            mistake(task.word)
            Result.TypingRevealed(task.word.main)
        } else {
            Result.TypingWrong(TextUtil.hint(task.word.main, showFirstLetter = true))
        }
    }

    fun answerMath(input: String): Result {
        val task = current as QuizTask.Math
        val value = input.trim().replace(" ", "").toLongOrNull()
        return if (value == task.answer) {
            done++
            Result.MathCorrect
        } else Result.MathWrong
    }

    /** Moves to the next task. Call after showing the feedback. */
    fun advance() {
        typingAttempts = 0
        sinceRetry++
        // A missed word returns after a couple of other questions.
        current = if (retryQueue.isNotEmpty() && sinceRetry >= 2) {
            sinceRetry = 0
            generator.retry(retryQueue.removeFirst())
        } else generator.next()
    }

    private fun success(word: Word) {
        done++
        correct += word.noteId
    }

    private fun mistake(word: Word) {
        missed += word.noteId
        if (required < baseRequired + MAX_PENALTY) required++
        if (retryQueue.none { it.noteId == word.noteId }) retryQueue.addLast(word)
        sinceRetry = 0
    }

    companion object {
        const val MAX_PENALTY = 5
    }
}
