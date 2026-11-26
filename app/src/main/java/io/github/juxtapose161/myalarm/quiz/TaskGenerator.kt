package io.github.juxtapose161.myalarm.quiz

import kotlin.random.Random

/**
 * Builds the morning tasks.
 *
 * @param words words in priority order (last mistakes first, then due cards, then the rest).
 * @param enabled task types switched on in the settings.
 * @param difficulty Sleep as Android difficulty, 1..5.
 */
class TaskGenerator(
    words: List<Word>,
    enabled: Set<TaskType>,
    private val difficulty: Int,
    private val random: Random = Random.Default,
) {
    private val pool: List<Word> = words.distinctBy { TextUtil.normalize(it.en) }
        .filter { it.en.isNotBlank() && it.ru.isNotBlank() }

    /** Types that can actually be produced with the words we have. */
    val types: List<TaskType> = run {
        val wordTypes = enabled.filter { it != TaskType.MATH }
            .filter { pool.isNotEmpty() }
            .filter { it == TaskType.TYPING || distinctCount() >= 2 }
            .filter { it != TaskType.CLOZE || pool.any { w -> clozeFor(w) != null } }
        val all = if (TaskType.MATH in enabled) wordTypes + TaskType.MATH else wordTypes
        all.ifEmpty { listOf(TaskType.MATH) } // never leave the user without a way to stop the alarm
    }

    val hasWords: Boolean get() = types.any { it != TaskType.MATH }

    private val optionCount = if (difficulty >= 4) 6 else 4
    private var cursor = 0
    private var lastType: TaskType? = null

    fun next(): QuizTask {
        val candidates = if (types.size > 1) types.filter { it != lastType } else types
        val type = candidates[random.nextInt(candidates.size)]
        lastType = type
        return build(type)
    }

    /** Re-asks a word the user just got wrong, as a typing or choice task. */
    fun retry(word: Word): QuizTask =
        if (TaskType.TYPING in types) QuizTask.Typing(word.ru, word) else choice(word) ?: math()

    private fun build(type: TaskType): QuizTask {
        if (type == TaskType.MATH) return math()
        val word = nextWord(type)
        return when (type) {
            TaskType.CHOICE -> choice(word)
            TaskType.AUDIO -> audio(word)
            TaskType.CLOZE -> cloze(word)
            TaskType.TYPING -> QuizTask.Typing(word.ru, word)
            TaskType.MATH -> null
        } ?: fallback(word)
    }

    private fun fallback(word: Word): QuizTask =
        choice(word) ?: if (TaskType.TYPING in types) QuizTask.Typing(word.ru, word) else math()

    private fun nextWord(type: TaskType): Word {
        val usable = if (type == TaskType.CLOZE) pool.filter { clozeFor(it) != null } else pool
        val list = usable.ifEmpty { pool }
        val w = list[cursor % list.size]
        cursor++
        return w
    }

    private fun distinctCount() = pool.map { TextUtil.normalize(it.ru) }.distinct().size

    private fun distractors(word: Word, key: (Word) -> String): List<String> {
        val own = TextUtil.normalize(key(word))
        return pool.asSequence()
            .filter { it.noteId != word.noteId }
            .map(key)
            .distinctBy { TextUtil.normalize(it) }
            .filter { TextUtil.normalize(it) != own }
            .toList()
            .shuffled(random)
            .take(optionCount - 1)
    }

    private fun options(correct: String, others: List<String>): Pair<List<String>, Int>? {
        if (others.isEmpty()) return null
        val all = (others + correct).shuffled(random)
        return all to all.indexOf(correct)
    }

    private fun choice(word: Word): QuizTask.Choice? {
        val enToRu = random.nextBoolean()
        return if (enToRu) {
            val (opts, idx) = options(word.ru, distractors(word) { it.ru }) ?: return null
            QuizTask.Choice(TaskType.CHOICE, "Выбери перевод", word.en, opts, idx, word)
        } else {
            val (opts, idx) = options(word.main, distractors(word) { it.main }) ?: return null
            QuizTask.Choice(TaskType.CHOICE, "Как это по-английски?", word.ru, opts, idx, word)
        }
    }

    private fun audio(word: Word): QuizTask.Choice? {
        val (opts, idx) = options(word.ru, distractors(word) { it.ru }) ?: return null
        return QuizTask.Choice(TaskType.AUDIO, "Послушай и выбери перевод", "🔊", opts, idx, word, speak = word.main)
    }

    private fun clozeFor(word: Word): String? =
        word.example?.let { TextUtil.makeCloze(it, word.variants) }

    private fun cloze(word: Word): QuizTask.Choice? {
        val sentence = clozeFor(word) ?: return null
        val (opts, idx) = options(word.main, distractors(word) { it.main }) ?: return null
        return QuizTask.Choice(TaskType.CLOZE, "Какое слово пропущено?", sentence, opts, idx, word)
    }

    fun math(): QuizTask.Math {
        fun r(from: Int, to: Int) = random.nextInt(from, to + 1).toLong()
        return when (difficulty.coerceIn(1, 5)) {
            1 -> { val a = r(10, 49); val b = r(10, 49); QuizTask.Math("$a + $b", a + b) }
            2 -> { val a = r(20, 99); val b = r(10, 99); val c = r(5, 40); QuizTask.Math("$a + $b − $c", a + b - c) }
            3 -> { val a = r(3, 9); val b = r(11, 29); val c = r(10, 99); QuizTask.Math("$a × $b + $c", a * b + c) }
            4 -> { val a = r(11, 19); val b = r(11, 19); val c = r(10, 99); QuizTask.Math("$a × $b + $c", a * b + c) }
            else -> { val a = r(12, 29); val b = r(12, 29); val c = r(3, 9); val d = r(11, 49); QuizTask.Math("$a × $b + $c × $d", a * b + c * d) }
        }
    }

    companion object {
        /** How many correct answers are needed to stop the alarm. */
        fun requiredFor(difficulty: Int): Int = when (difficulty.coerceIn(1, 5)) {
            1 -> 3; 2 -> 4; 3 -> 5; 4 -> 7; else -> 10
        }
    }
}
