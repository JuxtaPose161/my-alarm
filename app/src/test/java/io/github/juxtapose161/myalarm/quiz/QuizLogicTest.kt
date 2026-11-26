package io.github.juxtapose161.myalarm.quiz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QuizLogicTest {

    private val words = listOf(
        Word(1, "to run, ran", "бежать", "She was running late for work."),
        Word(2, "apple", "яблоко", "An apple a day keeps the doctor away."),
        Word(3, "decide", "решать", "We decided to stay home."),
        Word(4, "<b>window</b>", "окно"),
        Word(5, "carry", "нести", "He carried the bag upstairs."),
    ).map { it.copy(en = TextUtil.clean(it.en)) }

    @Test fun cleansHtmlAndSound() {
        assertEquals("cat dog & co", TextUtil.clean("<div>cat</div><br>dog&nbsp;&amp; co [sound:x.mp3]"))
        assertEquals("the cat sat", TextUtil.clean("the {{c1::cat::animal}} sat"))
    }

    @Test fun answerVariants() {
        assertEquals(listOf("run", "ran"), TextUtil.answerVariants("to run, ran (v.)"))
        assertEquals(listOf("look after"), TextUtil.answerVariants("to look after [phr]"))
    }

    @Test fun typedAnswers() {
        val v = TextUtil.answerVariants("beautiful")
        assertTrue(TextUtil.isTypedAnswerCorrect("Beautiful ", v))
        assertTrue(TextUtil.isTypedAnswerCorrect("beautifull", v)) // one typo forgiven
        assertFalse(TextUtil.isTypedAnswerCorrect("beatifl", v))
        assertFalse(TextUtil.isTypedAnswerCorrect("cat", TextUtil.answerVariants("car").plus("cart"))) // short words exact
        assertTrue(TextUtil.isTypedAnswerCorrect("to run", TextUtil.answerVariants("run")))
    }

    @Test fun cloze() {
        assertEquals("An _____ a day keeps the doctor away.",
            TextUtil.makeCloze("An apple a day keeps the doctor away.", listOf("apple")))
        assertEquals("She was _____ late for work.",
            TextUtil.makeCloze("She was running late for work.", listOf("run", "ran")))
        assertEquals("We _____ to stay home.", TextUtil.makeCloze("We decided to stay home.", listOf("decide")))
        assertEquals("He _____ the bag upstairs.", TextUtil.makeCloze("He carried the bag upstairs.", listOf("carry")))
        assertNull(TextUtil.makeCloze("Nothing here.", listOf("apple")))
        // must not match inside another word
        assertNull(TextUtil.makeCloze("Pineapples are sweet.", listOf("apple")))
    }

    @Test fun noWordsMeansMathOnly() {
        val g = TaskGenerator(emptyList(), TaskType.entries.toSet(), 3, Random(1))
        assertEquals(listOf(TaskType.MATH), g.types)
        assertFalse(g.hasWords)
        val g2 = TaskGenerator(emptyList(), setOf(TaskType.CHOICE), 3, Random(1))
        assertEquals(listOf(TaskType.MATH), g2.types) // never stuck without a task
    }

    @Test fun generatedTasksAreConsistent() {
        val g = TaskGenerator(words, TaskType.entries.toSet(), 5, Random(42))
        assertEquals(TaskType.entries.toSet(), g.types.toSet())
        repeat(300) {
            when (val t = g.next()) {
                is QuizTask.Choice -> {
                    assertTrue(t.options.size >= 2)
                    assertEquals(t.options.size, t.options.map { TextUtil.normalize(it) }.distinct().size)
                    val correct = t.options[t.correctIndex]
                    when {
                        t.type == TaskType.CLOZE -> { assertEquals(t.word.main, correct); assertTrue("_____" in t.prompt) }
                        t.prompt == t.word.en || t.type == TaskType.AUDIO -> assertEquals(t.word.ru, correct)
                        else -> assertEquals(t.word.main, correct)
                    }
                    if (t.type == TaskType.AUDIO) assertNotNull(t.speak)
                }
                is QuizTask.Typing -> assertEquals(t.word.ru, t.prompt)
                is QuizTask.Math -> assertTrue(t.answer > 0)
            }
        }
    }

    @Test fun mathMatchesExpression() {
        for (d in 1..5) {
            val g = TaskGenerator(emptyList(), setOf(TaskType.MATH), d, Random(d))
            repeat(50) {
                val m = g.math()
                val expr = m.expression.replace("×", "*").replace("−", "-")
                assertEquals(m.expression, eval(expr), m.answer)
            }
        }
    }

    @Test fun sessionPenaltiesAreCapped() {
        val g = TaskGenerator(words, setOf(TaskType.CHOICE), 1, Random(7))
        val s = QuizSession(g, TaskGenerator.requiredFor(1))
        assertEquals(3, s.required)
        var guard = 0
        // answer everything wrong 20 times
        while (guard++ < 20) {
            when (val t = s.current) {
                is QuizTask.Choice -> s.answerChoice((t.correctIndex + 1) % t.options.size)
                is QuizTask.Typing -> { s.answerTyping("zzz"); s.answerTyping("zzz") }
                is QuizTask.Math -> s.answerMath("-1")
            }
            s.advance()
        }
        assertEquals(3 + QuizSession.MAX_PENALTY, s.required)
        assertEquals(0, s.done)
        // now answer correctly until finished
        guard = 0
        while (!s.isFinished && guard++ < 100) {
            when (val t = s.current) {
                is QuizTask.Choice -> s.answerChoice(t.correctIndex)
                is QuizTask.Typing -> s.answerTyping(t.word.main)
                is QuizTask.Math -> s.answerMath(t.answer.toString())
            }
            if (!s.isFinished) s.advance()
        }
        assertTrue(s.isFinished)
        assertTrue(s.missed.isNotEmpty())
    }

    @Test fun typingRevealsAfterTwoMisses() {
        val g = TaskGenerator(words, setOf(TaskType.TYPING), 1, Random(3))
        val s = QuizSession(g, 3)
        val t = s.current as QuizTask.Typing
        assertTrue(s.answerTyping("nope") is QuizSession.Result.TypingWrong)
        val r = s.answerTyping("nope again")
        assertTrue(r is QuizSession.Result.TypingRevealed)
        assertEquals(t.word.main, (r as QuizSession.Result.TypingRevealed).answer)
        assertEquals(4, s.required)
    }

    /** Tiny evaluator for "a * b + c * d", "a + b - c". */
    private fun eval(expr: String): Long {
        val tokens = expr.split(" ")
        val terms = mutableListOf<Long>()
        val ops = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            var v = tokens[i].toLong()
            while (i + 2 < tokens.size && tokens[i + 1] == "*") { v *= tokens[i + 2].toLong(); i += 2 }
            terms += v
            if (i + 1 < tokens.size) ops += tokens[i + 1]
            i += 2
        }
        var acc = terms[0]
        for (k in ops.indices) acc = if (ops[k] == "+") acc + terms[k + 1] else acc - terms[k + 1]
        return acc
    }
}
