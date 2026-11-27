package io.github.juxtapose161.myalarm.course

import io.github.juxtapose161.myalarm.quiz.TextUtil

/** Parses a dictation text with [[gaps]] and checks the answers locally. */
class Dictation(val title: String, text: String) {

    constructor(task: TaskFile) : this(task.title, task.dictation)

    sealed class Part {
        data class Text(val text: String) : Part()
        data class Gap(val index: Int, val answer: String) : Part()
    }

    val parts: List<Part>
    val gaps: List<Part.Gap>

    /** The text without brackets, for text-to-speech. */
    val spoken: String

    init {
        val list = mutableListOf<Part>()
        var last = 0
        var n = 0
        for (m in GAP.findAll(text)) {
            if (m.range.first > last) list += Part.Text(text.substring(last, m.range.first))
            list += Part.Gap(n++, m.groupValues[1].trim())
            last = m.range.last + 1
        }
        if (last < text.length) list += Part.Text(text.substring(last))
        parts = list
        gaps = list.filterIsInstance<Part.Gap>()
        spoken = GAP.replace(text) { it.groupValues[1] }
    }

    /** How many gaps may be wrong and the dictation still counts. */
    val allowedMistakes: Int get() = (gaps.size / 8).coerceAtLeast(if (gaps.size >= 4) 1 else 0)

    fun isRight(gap: Part.Gap, input: String): Boolean {
        val a = TextUtil.normalize(gap.answer)
        val b = TextUtil.normalize(input)
        return a == b || (a.length >= 6 && TextUtil.levenshtein(a, b) <= 1)
    }

    /** Indices of wrong gaps. */
    fun check(inputs: List<String>): List<Int> =
        gaps.filter { !isRight(it, inputs.getOrElse(it.index) { "" }) }.map { it.index }

    fun passed(inputs: List<String>) = gaps.isNotEmpty() && check(inputs).size <= allowedMistakes

    companion object {
        private val GAP = Regex("""\[\[([^\]]+)]]""")
    }
}
