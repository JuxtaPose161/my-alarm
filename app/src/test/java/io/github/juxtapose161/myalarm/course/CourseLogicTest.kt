package io.github.juxtapose161.myalarm.course

import io.github.juxtapose161.myalarm.quiz.TextUtil
import io.github.juxtapose161.myalarm.quiz.TranslateSession
import io.github.juxtapose161.myalarm.quiz.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.regex.Pattern
import kotlin.random.Random

class CourseLogicTest {

    private val words = listOf(
        Word(1, "to run, ran", "бежать, бегать"),
        Word(2, "apple", "яблоко"),
        Word(3, "decide", "решать (принимать решение)"),
        Word(4, "window", "окно"),
    )

    /** The example data repository in tutor/examples (also checked by tutor/validate.py). */
    private val examples = File("../tutor/examples")
    private fun example(name: String): TaskFile =
        AppJson.decodeFromString(TaskFile.serializer(), File(examples, "tasks/$name.json").readText())

    @Test fun russianVariants() {
        assertEquals(listOf("бежать", "бегать"), TextUtil.russianVariants("бежать, бегать"))
        assertTrue("решать" in TextUtil.russianVariants("решать (принимать решение)"))
        assertTrue(TextUtil.isRussianAnswerCorrect("Яблоко", listOf("яблоко")))
        assertTrue(TextUtil.isRussianAnswerCorrect("решенье", listOf("решение"))) // one typo in a long word
        assertFalse(TextUtil.isRussianAnswerCorrect("окна", listOf("окно")))     // short words must be exact
        assertTrue(TextUtil.isRussianAnswerCorrect("ёлка", listOf("елка")))
    }

    @Test fun translateAlternatesAndSkipsOnMistake() {
        val s = TranslateSession(words, required = 3)
        val first = s.current!!
        assertTrue(first.toEnglish)                 // RU → EN first
        assertTrue(s.checkLocally("run"))
        assertTrue(s.checkLocally("to run"))
        s.answer(true)
        val second = s.current!!
        assertFalse(second.toEnglish)               // then EN → RU
        assertTrue(s.checkLocally("яблоко"))
        assertFalse(s.checkLocally("груша"))
        s.answer(false)                             // skipped
        assertEquals(1, s.done)
        assertTrue(2L in s.missed)
        s.answer(true); s.answer(true)
        assertTrue(s.isFinished)
        assertNull(s.current)
    }

    @Test fun translateReturnsSkippedWords() {
        val s = TranslateSession(words.take(2), required = 2)
        s.answer(false) // word 1 skipped
        s.answer(true)  // word 2 ok
        assertEquals(1L, s.current!!.word.noteId)
        s.answer(true)
        assertTrue(s.isFinished)
    }

    @Test fun translateAcceptsArticleAndSeveralVariants() {
        val card = TranslateSession.Card(Word(9, "apple", "яблоко"), toEnglish = true)
        assertTrue(TranslateSession.isCorrect("an apple", card))
        val ru = TranslateSession.Card(Word(1, "run", "бежать, бегать"), toEnglish = false)
        assertTrue(TranslateSession.isCorrect("бегать", ru))
        assertTrue(TranslateSession.isCorrect("бежать, бегать", ru))
        assertFalse(TranslateSession.isCorrect("бежать, идти", ru)) // every given variant must be right
    }

    @Test fun dictationParsesAndChecks() {
        val d = Dictation("t", "She [[decided]] to buy an [[apple]] and [[went]] home.")
        assertEquals(3, d.gaps.size)
        assertEquals("She decided to buy an apple and went home.", d.spoken)
        assertEquals(emptyList<Int>(), d.check(listOf("decided", "Apple", "went")))
        assertEquals(listOf(2), d.check(listOf("decidet", "apple", "go"))) // typo forgiven in a long word
        assertFalse(d.passed(listOf("decided", "apple", "go"))) // fewer than 4 gaps: no mistakes allowed
        val ex = Dictation(example("20261128-0215-dictation-1"))
        assertEquals(8, ex.gaps.size)
        assertEquals(1, ex.allowedMistakes)
    }

    @Test fun scenarioWindows() {
        val morning = Scenario("m", "m", 4 * 60, 11 * 60, 3, listOf("TRANSLATE"), escapeMinutes = 15)
        val night = Scenario("n", "n", 22 * 60, 4 * 60, 0, listOf("DICTATION"), escapeMinutes = 0)
        assertTrue(morning.contains(7 * 60))
        assertFalse(morning.contains(11 * 60))
        assertTrue(night.contains(23 * 60))
        assertTrue(night.contains(60))
        assertFalse(night.contains(12 * 60))
        assertEquals("n", Scenario.pick(listOf(morning, night), 23 * 60).id)
        assertEquals("m", Scenario.pick(listOf(morning, night), 15 * 60).id) // no match → first
    }

    @Test fun chooseKindPrefersLeastRecentAvailable() {
        val kinds = listOf(TaskKind.DIALOG, TaskKind.RETELL, TaskKind.TRANSLATE)
        val all: (TaskKind) -> Boolean = { true }
        assertEquals(TaskKind.TRANSLATE, Scenario.chooseKind(kinds, listOf("RETELL", "DIALOG"), all, TaskKind.QUICK, Random(1)))
        assertEquals(TaskKind.RETELL, Scenario.chooseKind(kinds, listOf("RETELL", "DIALOG", "TRANSLATE"), all, TaskKind.QUICK, Random(1)))
        // Without a prepared dialog the next best is taken; with nothing available, the fallback.
        assertEquals(TaskKind.TRANSLATE, Scenario.chooseKind(kinds, emptyList(), { it == TaskKind.TRANSLATE }, TaskKind.QUICK))
        assertEquals(TaskKind.QUICK, Scenario.chooseKind(listOf(TaskKind.DIALOG), emptyList(), { false }, TaskKind.QUICK))
    }

    @Test fun defaultScenariosUseExistingKinds() {
        Scenario.defaults.forEach { s -> assertEquals(s.kinds.size, s.kindList.size) }
    }

    // --- the phone's check of prepared tasks ---

    @Test fun phraseMatchingIsWholeWord() {
        assertTrue(LocalCheck.contains("I Gave Up last year", "gave up"))
        assertFalse(LocalCheck.contains("pineapple", "apple"))
        assertTrue(LocalCheck.contains("it’s on foot", "it's on foot"))
        assertEquals(listOf("first of all", "in the end", "however", "because"), LocalCheck.linkersUsed("First of all, however, because of that... In the end"))
    }

    @Test fun retellGoodAndLazyAnswers() {
        val t = example("20261128-0215-retell-1")
        val good = "Maria lost her job at a bank and decided to open a bakery, because she had always dreamed about it. " +
            "At first only her friends came and she almost gave up. Then a student filmed her at night and the video " +
            "got millions of views on the internet. Now there are queues every morning and she has hired four people. " +
            "She earns less money than before but she is really happy."
        val answers = listOf("only her friends came", "because of a video a student posted online", "it was her dream and she loves baking")
        val r = LocalCheck.check(t, good, answers)
        assertTrue(r.criteria.toString(), r.passed)

        val lazy = LocalCheck.check(t, "A woman had a shop. It was nice. People like it.", listOf("yes", "", "money"))
        assertFalse(lazy.passed)
        assertTrue(lazy.criteria.any { it.name == "Ключевые моменты" && !it.ok })
        assertTrue(lazy.criteria.any { it.name == "Слова из Anki" && !it.ok })
    }

    @Test fun dialogNeedsWordsGrammarAndTime() {
        val t = example("20261128-0215-dialog-1")
        val replies = listOf(
            "Sure! Go straight on until you reach the big crossroads, then turn left.",
            "It is about fifteen minutes on foot, but you should take a taxi with that suitcase.",
            "If you miss it, you can take the next one, they go every hour.",
            "I recommend the little cafe opposite the station, their coffee is great.",
            "No, I have never been to Portugal, but I would love to go.",
        )
        assertTrue(LocalCheck.check(t, "", replies, elapsedSeconds = 301).passed)
        val early = LocalCheck.check(t, "", replies, elapsedSeconds = 120)
        assertFalse(early.passed)
        assertTrue(early.criteria.single { it.name == "Время" }.comment.contains("3"))
        val short = replies.toMutableList().also { it[2] = "Okay." }
        assertFalse(LocalCheck.check(t, "", short, elapsedSeconds = 400).passed)
    }

    @Test fun rephraseChecksTheConstructionPerItem() {
        val t = example("20261128-0215-rephrase-1")
        val good = listOf(
            "The old printer was repaired by the new manager yesterday, so we can finally print our reports.",
            "If the new manager didn't repair printers, we couldn't print our reports.",
            "A colleague said that the new manager had repaired the old printer.",
        )
        assertTrue(LocalCheck.check(t, "", good).criteria.toString(), LocalCheck.check(t, "", good).passed)
        val bad = good.toMutableList().also { it[0] = "The new manager repaired the old printer yesterday." }
        val r = LocalCheck.check(t, "", bad)
        assertFalse(r.passed)
        assertEquals("№1", r.criteria.first { !it.ok }.name)
    }

    @Test fun errorsCountsFixedFragments() {
        val t = example("20261128-0215-errors-1")
        val answer = "I am writing you → I am writing to you\na help → need help\nYesterday I have finished → yesterday I finished\n" +
            "My boss want → my boss wants"
        assertTrue(LocalCheck.check(t, answer, emptyList()).passed)
        assertFalse(LocalCheck.check(t, "a help → need help\nmy boss want → boss wants", emptyList()).passed)
    }

    @Test fun brokenPatternIsSkippedNotFailed() {
        val t = TaskFile(id = "x", kind = "DAY_PLAN", level = "B1", title = "t", instructionsRu = "i",
            grammar = listOf(GrammarCheck("a2_going_to", "([unclosed")), needGrammar = 1)
        val r = LocalCheck.check(t, "I am going to work", emptyList())
        assertTrue(r.passed)
    }

    @Test fun examplePatternsCompileInJavaToo() {
        File(examples, "tasks").listFiles()!!.forEach { f ->
            val t = AppJson.decodeFromString(TaskFile.serializer(), f.readText())
            assertNotNull(t.taskKind)
            (t.grammar + t.items.mapNotNull { it.grammar }).forEach { g ->
                val p = Pattern.compile(g.pattern, Pattern.CASE_INSENSITIVE)
                g.examples.forEach { ex -> assertTrue("${f.name}: $ex", p.matcher(LocalCheck.normalize(ex)).find()) }
            }
        }
    }

    @Test fun profileParses() {
        val p = AppJson.decodeFromString(LearnerProfile.serializer(), File(examples, "profile.json").readText())
        assertEquals("B1", p.level)
        assertTrue(p.isMastered("a2_past_simple"))
        assertFalse(p.isMastered("b1_present_perfect_for_since"))
        assertEquals(listOf("exhausting"), p.struggling(5))
    }

    @Test fun answerRoundTrips() {
        val a = AnswerFile(taskId = "t", kind = "RETELL", answeredAt = "now", main = "text", items = listOf("a"),
            criteria = listOf(Criterion("Объём", true)), passed = true)
        assertEquals(a, AppJson.decodeFromString(AnswerFile.serializer(), AppJson.encodeToString(AnswerFile.serializer(), a)))
    }
}
