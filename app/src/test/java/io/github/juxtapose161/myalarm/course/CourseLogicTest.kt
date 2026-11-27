package io.github.juxtapose161.myalarm.course

import io.github.juxtapose161.myalarm.quiz.TextUtil
import io.github.juxtapose161.myalarm.quiz.TranslateSession
import io.github.juxtapose161.myalarm.quiz.Word
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CourseLogicTest {

    private val words = listOf(
        Word(1, "to run, ran", "бежать, бегать"),
        Word(2, "apple", "яблоко"),
        Word(3, "decide", "решать (принимать решение)"),
        Word(4, "window", "окно"),
    )

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
        val again = s.current!!
        assertEquals(1L, again.word.noteId)
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
        val d = Dictation(DictationSpec("t", "She [[decided]] to buy an [[apple]] and [[went]] home."))
        assertEquals(3, d.gaps.size)
        assertEquals("She decided to buy an apple and went home.", d.spoken)
        assertEquals(emptyList<Int>(), d.check(listOf("decided", "Apple", "went")))
        assertEquals(listOf(2), d.check(listOf("decidet", "apple", "go"))) // typo forgiven in a long word
        assertTrue(d.passed(listOf("decided", "apple", "went")))
        assertFalse(d.passed(listOf("decided", "apple", "go"))) // fewer than 4 gaps: no mistakes allowed
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

    @Test fun chooseKindPrefersLeastRecent() {
        val kinds = listOf(TaskKind.DIALOG, TaskKind.RETELL, TaskKind.TRANSLATE)
        val history = listOf(
            LearnerProfile.HistoryEntry("d", "RETELL", "", true),
            LearnerProfile.HistoryEntry("d", "DIALOG", "", true),
        )
        assertEquals(TaskKind.TRANSLATE, Scenario.chooseKind(kinds, history, true, true, Random(1)))
        val history2 = history + LearnerProfile.HistoryEntry("d", "TRANSLATE", "", true)
        assertEquals(TaskKind.RETELL, Scenario.chooseKind(kinds, history2, true, true, Random(1)))
        // Without Claude only offline kinds remain.
        assertEquals(TaskKind.TRANSLATE, Scenario.chooseKind(listOf(TaskKind.DIALOG), emptyList(), false, true))
        assertEquals(TaskKind.QUICK, Scenario.chooseKind(listOf(TaskKind.DIALOG), emptyList(), false, false))
    }

    @Test fun profileTracksGrammarAndFocus() {
        var p = LearnerProfile(level = "B1")
        val first = p.focusGrammar(3).map { it.id }
        assertEquals(Curriculum.level("B1").grammar.take(3).map { it.id }, first)
        repeat(3) { p = p.withResult("d", TaskKind.RETELL, "t", true, grammarOk = listOf(first[0])) }
        assertTrue(p.isMastered(first[0]))
        assertFalse(first[0] in p.focusGrammar(3).map { it.id })
        // A weak point from a lower level comes back for review.
        p = p.withResult("d", TaskKind.ERRORS, "t", false, grammarErrors = listOf("a2_past_simple", "a2_past_simple", "bogus"))
        assertTrue("a2_past_simple" in p.focusGrammar(3).map { it.id })
        assertNull(p.grammar["bogus"])
        assertTrue(p.describe().contains("a2_past_simple"))
    }

    @Test fun profileRoundTripsAsJson() {
        val p = LearnerProfile(level = "C1").withResult("2026-11-27", TaskKind.DIALOG, "Interview", true,
            grammarOk = listOf("c1_inversion"), usedWords = listOf("Decide"), missedWords = listOf("apple"))
        val back = AppJson.decodeFromString<LearnerProfile>(AppJson.encodeToString(p))
        assertEquals(p, back)
        assertEquals(listOf("apple"), back.struggling(5))
    }

    @Test fun evaluationParsesFromModelJson() {
        val json = """{"criteria":[{"name":"Суть","ok":true,"comment":""},{"name":"Слова","ok":false,"comment":"2 из 3"}],
            "corrections":[{"wrong":"he go","right":"he goes","why":"3-е лицо"}],"feedbackRu":"Хорошо","usedWords":["decide"],
            "grammarOk":[],"grammarErrors":["a1_present_simple"],"strengths":[],"weaknesses":["окончание -s"]}"""
        val e = AppJson.decodeFromString<Evaluation>(json)
        assertFalse(e.passed)
        assertEquals("he goes", e.corrections[0].right)
    }

    @Test fun promptsMentionTheTask() {
        val p = LearnerProfile(level = "B2")
        val focus = p.focusGrammar(3)
        for (kind in TaskKind.entries.filter { it.pregenerated }) {
            val text = Prompts.generate(kind, p, TaskParams(), listOf("decide", "apple"), focus)
            assertTrue(kind.name, text.contains(kind.name))
            assertTrue(text.contains("decide"))
        }
    }

    @Test fun schemasRequireAllFields() {
        @Suppress("UNCHECKED_CAST")
        val props = (Schemas.written["properties"] as Map<String, Any>).keys
        assertEquals(props.toList(), Schemas.written["required"])
        assertEquals(false, Schemas.evaluation["additionalProperties"])
    }
}
