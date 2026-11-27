package io.github.juxtapose161.myalarm.quiz

/** Text helpers for Anki field contents. Pure Kotlin so they can be unit-tested off-device. */
object TextUtil {

    private val soundTag = Regex("""\[sound:[^\]]*]""")
    private val lineBreak = Regex("""(?i)<br\s*/?>|</div>|</p>|</li>""")
    private val htmlTag = Regex("""<[^>]+>""")
    private val clozeMarkup = Regex("""\{\{c\d+::(.*?)(::[^}]*)?}}""")
    private val spaces = Regex("""\s+""")
    private val numericEntity = Regex("""&#(x?[0-9a-fA-F]+);""")
    private val entities = mapOf(
        "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">",
        "&quot;" to "\"", "&apos;" to "'", "&#39;" to "'", "&mdash;" to "—", "&ndash;" to "–",
    )

    /** Strips HTML, sound tags and cloze markup from an Anki field; collapses whitespace. */
    fun clean(field: String): String {
        var s = soundTag.replace(field, " ")
        s = clozeMarkup.replace(s) { it.groupValues[1] }
        s = lineBreak.replace(s, " ")
        s = htmlTag.replace(s, "")
        for ((k, v) in entities) s = s.replace(k, v)
        s = numericEntity.replace(s) { m ->
            val code = m.groupValues[1]
            val n = if (code.startsWith("x")) code.drop(1).toIntOrNull(16) else code.toIntOrNull()
            n?.let { String(Character.toChars(it)) } ?: m.value
        }
        return spaces.replace(s, " ").trim()
    }

    /** Lowercase, unify apostrophes and dashes, drop surrounding punctuation. */
    fun normalize(s: String): String =
        s.lowercase()
            .replace('’', '\'').replace('‘', '\'').replace('`', '\'')
            .replace('ё', 'е')
            .replace(Regex("""[\s ]+"""), " ")
            .trim()
            .trim('.', ',', '!', '?', ';', ':', '"', '«', '»')
            .trim()

    /**
     * Accepted answers for an English field like "to run, ran (v.)" -> ["run", "ran"].
     * Splits on , ; / |, removes parentheses and a leading "to ".
     */
    fun answerVariants(field: String): List<String> =
        clean(field)
            .replace(Regex("""\([^)]*\)|\[[^]]*]"""), " ")
            .split(',', ';', '/', '|')
            .map { normalize(it) }
            .map { it.removePrefix("to ").trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    /**
     * Accepted Russian answers for a field like "бежать, бегать (быстро); мчаться" ->
     * ["бежать", "бегать", "мчаться"]. Text in parentheses is a comment, not part of the answer.
     */
    fun russianVariants(field: String): List<String> {
        val cleaned = clean(field)
        val withoutNotes = cleaned.replace(Regex("""\([^)]*\)|\[[^]]*]"""), " ")
        val parts = withoutNotes.split(',', ';', '/', '|').map { normalize(it) }.filter { it.isNotEmpty() }
        // "(с)делать" style: also accept the variant with the bracketed letters kept.
        val keepBrackets = cleaned.replace("(", "").replace(")", "")
            .split(',', ';', '/', '|').map { normalize(it) }.filter { it.isNotEmpty() }
        return (parts + keepBrackets).distinct()
    }

    /** Russian answer check: exact after normalisation, one typo forgiven in words of 6+ letters. */
    fun isRussianAnswerCorrect(input: String, variants: List<String>): Boolean {
        val n = normalize(input)
        if (n.isEmpty()) return false
        return variants.any { v -> n == v || (v.length >= 6 && levenshtein(n, v) <= 1) }
    }

    /** "the apple" -> "apple": dictation often adds an article. */
    fun stripArticle(s: String): String = s.trim().replace(Regex("""(?i)^(a|an|the)\s+"""), "")

    /** The main form shown in hints and choices: first variant of the field. */
    fun primary(field: String): String = answerVariants(field).firstOrNull() ?: normalize(clean(field))

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** One typo is forgiven for words of 6+ letters. */
    fun isTypedAnswerCorrect(input: String, variants: List<String>): Boolean {
        val n = normalize(input).removePrefix("to ").trim()
        if (n.isEmpty()) return false
        return variants.any { v -> n == v || (v.length >= 6 && levenshtein(n, v) <= 1) }
    }

    /**
     * Hides the word in an example sentence. Tries the exact form first, then an inflected one
     * ("run" -> "running") by matching a token that starts with the word's stem.
     * Returns null when the word cannot be found.
     */
    fun makeCloze(example: String, variants: List<String>): String? {
        val sentence = clean(example)
        if (sentence.isEmpty()) return null
        val blank = "_____"
        for (v in variants.sortedByDescending { it.length }) {
            val exact = Regex("""(?i)(?<![\p{L}])${Regex.escape(v)}(?![\p{L}])""")
            if (exact.containsMatchIn(sentence)) return exact.replaceFirst(sentence, blank)
        }
        for (v in variants) {
            if (v.contains(' ') || v.length < 3) continue
            val stem = if (v.length >= 5) v.dropLast(if (v.endsWith("e") || v.endsWith("y")) 1 else 0) else v
            val token = Regex("""(?i)(?<![\p{L}])${Regex.escape(stem)}\p{L}{0,4}(?![\p{L}])""")
            if (token.containsMatchIn(sentence)) return token.replaceFirst(sentence, blank)
        }
        return null
    }

    /** "apple" -> "a _ _ _ _" (first letter shown) or "_ _ _ _ _". */
    fun hint(word: String, showFirstLetter: Boolean): String =
        word.mapIndexed { i, c ->
            when {
                c == ' ' -> "  "
                i == 0 && showFirstLetter -> c.toString()
                c.isLetter() -> "_"
                else -> c.toString()
            }
        }.joinToString(" ")
}
