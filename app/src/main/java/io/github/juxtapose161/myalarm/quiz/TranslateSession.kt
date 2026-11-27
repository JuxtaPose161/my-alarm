package io.github.juxtapose161.myalarm.quiz

/**
 * Task 1: translate N words. Directions alternate RU→EN and EN→RU. One attempt per card:
 * an answer without mistakes counts, otherwise the card is skipped (and remembered as a mistake).
 * Skipped words come back at the end, in the other direction, when the deck runs out.
 */
class TranslateSession(words: List<Word>, val required: Int) {

    data class Card(val word: Word, val toEnglish: Boolean) {
        val prompt: String get() = if (toEnglish) word.ru else word.en
        /** All accepted answers. */
        val answers: List<String> get() = if (toEnglish) word.variants else TextUtil.russianVariants(word.ru)
        val shownAnswer: String get() = if (toEnglish) word.en else word.ru
    }

    private val pool = words.filter { it.en.isNotBlank() && it.ru.isNotBlank() }.distinctBy { it.noteId }
    private val queue = ArrayDeque(pool)
    private val skipped = ArrayDeque<Word>()
    private var index = 0

    var done = 0; private set
    val missed = linkedSetOf<Long>()
    val correct = linkedSetOf<Long>()
    val isFinished get() = done >= required
    val isEmpty get() = pool.isEmpty()

    var current: Card? = nextCard(); private set

    private fun nextCard(): Card? {
        val word = queue.removeFirstOrNull() ?: skipped.removeFirstOrNull()?.also { queue.addAll(skipped); skipped.clear() }
            ?: return null
        val card = Card(word, toEnglish = index % 2 == 0)
        index++
        return card
    }

    /** Local check: exact match with one of the variants (one typo forgiven in long words). */
    fun checkLocally(input: String): Boolean {
        val card = current ?: return false
        return isCorrect(input, card)
    }

    /** Records the verdict for the current card and moves on. */
    fun answer(ok: Boolean) {
        val card = current ?: return
        if (ok) {
            done++
            correct += card.word.noteId
        } else {
            missed += card.word.noteId
            skipped.addLast(card.word)
        }
        current = if (isFinished) null else nextCard()
    }

    companion object {
        fun isCorrect(input: String, card: Card): Boolean {
            // Several translations may be given separated by commas; each of them must be right.
            val parts = input.split(',', ';', '/').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return false
            return parts.all { part ->
                if (card.toEnglish) TextUtil.isTypedAnswerCorrect(part, card.answers) ||
                    TextUtil.isTypedAnswerCorrect(TextUtil.stripArticle(part), card.answers)
                else TextUtil.isRussianAnswerCorrect(part, card.answers)
            }
        }
    }
}
