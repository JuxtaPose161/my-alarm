package io.github.juxtapose161.myalarm.course

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Checks an answer on the phone, by the criteria Claude wrote into the task. It is a strict but
 * mechanical check (words, constructions by pattern, key points, length); the detailed review
 * comes later from the scheduled Claude session.
 */
object LocalCheck {

    data class Result(val criteria: List<Criterion>) {
        val passed get() = criteria.all { it.ok }
    }

    val linkers = listOf(
        "first of all", "to begin with", "firstly", "secondly", "finally", "in the end", "then", "after that",
        "however", "although", "though", "even though", "despite", "in spite of", "whereas", "while",
        "on the other hand", "moreover", "in addition", "besides", "furthermore", "what is more",
        "because", "since", "as a result", "therefore", "so that", "which is why", "that's why",
        "for example", "for instance", "such as", "overall", "to sum up", "in conclusion", "actually", "anyway",
    )

    fun normalize(s: String): String =
        s.lowercase().replace('’', '\'').replace('‘', '\'').replace('`', '\'').replace(Regex("""\s+"""), " ").trim()

    fun wordCount(s: String): Int = s.split(Regex("""\s+""")).count { it.any(Char::isLetter) }

    /** Whole-word / whole-phrase match. */
    fun contains(text: String, phrase: String): Boolean {
        val p = normalize(phrase)
        if (p.isEmpty()) return false
        val body = p.split(' ').joinToString("""\s+""") { Pattern.quote(it) }
        return Regex("""(?<![\p{L}'])$body(?![\p{L}])""").containsMatchIn(normalize(text))
    }

    fun usesWord(text: String, w: WordCheck): Boolean = (listOf(w.word) + w.forms).any { contains(text, it) }

    /** Null when the pattern is broken: then the check is skipped rather than failing the learner. */
    fun matches(text: String, g: GrammarCheck): Boolean? = try {
        Pattern.compile(g.pattern, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE).matcher(normalize(text)).find()
    } catch (e: PatternSyntaxException) {
        null
    }

    fun hasPoint(text: String, p: KeyPoint): Boolean = p.anyOf.any { contains(text, it) }

    fun linkersUsed(text: String): List<String> = linkers.filter { contains(text, it) }

    fun check(task: TaskFile, main: String, items: List<String>, elapsedSeconds: Long = Long.MAX_VALUE): Result {
        val all = (listOf(main) + items).filter { it.isNotBlank() }.joinToString("\n")
        val out = mutableListOf<Criterion>()

        if (task.minWords > 0) {
            val n = wordCount(if (task.taskKind?.mode == AnswerMode.DIALOG) all else main.ifBlank { all })
            out += Criterion("Объём", n >= task.minWords, "$n из ${task.minWords} слов")
        }
        if (task.needWords > 0 && task.words.isNotEmpty()) {
            val used = task.words.filter { usesWord(all, it) }.map { it.word }
            val need = minOf(task.needWords, task.words.size)
            out += Criterion("Слова из Anki", used.size >= need,
                "${used.size} из $need" + if (used.isNotEmpty()) ": ${used.joinToString()}" else "")
        }
        if (task.needGrammar > 0 && task.grammar.isNotEmpty()) {
            val results = task.grammar.map { it to matches(all, it) }
            val usable = results.filter { it.second != null }
            val used = usable.filter { it.second == true }.map { it.first }
            val need = minOf(task.needGrammar, usable.size)
            if (need > 0) out += Criterion("Грамматика урока", used.size >= need,
                "${used.size} из $need" + if (used.isNotEmpty()) ": ${used.joinToString { it.hintRu.ifBlank { it.id } }}" else "")
        }
        if (task.needPoints > 0 && task.points.isNotEmpty()) {
            val missing = task.points.filter { !hasPoint(all, it) }
            val have = task.points.size - missing.size
            val need = minOf(task.needPoints, task.points.size)
            out += Criterion("Ключевые моменты", have >= need,
                "$have из $need" + if (missing.isNotEmpty() && have < need) ". Не хватает: ${missing.joinToString { it.nameRu }}" else "")
        }
        if (task.needErrors > 0 && task.errors.isNotEmpty()) {
            val found = task.errors.count { e -> e.right.any { contains(all, it) } }
            val need = minOf(task.needErrors, task.errors.size)
            out += Criterion("Найдены ошибки", found >= need, "$found из $need")
        }
        if (task.needLinkers > 0) {
            val l = linkersUsed(all)
            out += Criterion("Слова-связки", l.size >= task.needLinkers,
                "${l.size} из ${task.needLinkers}" + if (l.isNotEmpty()) ": ${l.joinToString()}" else "")
        }
        task.items.forEachIndexed { i, item ->
            val a = items.getOrElse(i) { "" }
            val label = "№${i + 1}"
            if (a.isBlank() && (item.points.isNotEmpty() || item.grammar != null || item.minWords > 0)) {
                out += Criterion(label, false, "нет ответа")
                return@forEachIndexed
            }
            if (item.minWords > 0 && wordCount(a) < item.minWords) {
                out += Criterion(label, false, "слишком коротко: ${wordCount(a)} из ${item.minWords} слов")
                return@forEachIndexed
            }
            item.grammar?.let { g ->
                val m = matches(a, g)
                if (m == false) {
                    out += Criterion(label, false, "нет конструкции: ${g.hintRu.ifBlank { g.id }}")
                    return@forEachIndexed
                }
            }
            if (item.needPoints > 0 && item.points.isNotEmpty()) {
                val have = item.points.count { hasPoint(a, it) }
                val need = minOf(item.needPoints, item.points.size)
                if (have < need) {
                    out += Criterion(label, false, "не хватает сути: ${item.points.filter { !hasPoint(a, it) }.joinToString { it.nameRu }}")
                    return@forEachIndexed
                }
            }
            if (item.points.isNotEmpty() || item.grammar != null || item.minWords > 0) out += Criterion(label, true)
        }
        if (task.minMinutes > 0) {
            val ok = elapsedSeconds >= task.minMinutes * 60L
            out += Criterion("Время", ok, if (ok) "${task.minMinutes} мин" else "ещё ${(task.minMinutes * 60L - elapsedSeconds + 59) / 60} мин")
        }
        return Result(out)
    }
}
