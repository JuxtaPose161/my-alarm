package io.github.juxtapose161.myalarm.ui

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.urbandroid.sleep.captcha.CaptchaSupport
import com.urbandroid.sleep.captcha.CaptchaSupportFactory
import com.urbandroid.sleep.captcha.RemainingTimeListener
import io.github.juxtapose161.myalarm.SettingsStore
import io.github.juxtapose161.myalarm.anki.AnkiRepository
import io.github.juxtapose161.myalarm.course.AnswerFile
import io.github.juxtapose161.myalarm.course.CourseStore
import io.github.juxtapose161.myalarm.course.Dictation
import io.github.juxtapose161.myalarm.course.Engine
import io.github.juxtapose161.myalarm.course.LocalHistory
import io.github.juxtapose161.myalarm.course.Scenario
import io.github.juxtapose161.myalarm.course.TaskKind
import io.github.juxtapose161.myalarm.data.Sync
import io.github.juxtapose161.myalarm.quiz.QuizSession
import io.github.juxtapose161.myalarm.quiz.TaskGenerator
import io.github.juxtapose161.myalarm.quiz.TaskType
import io.github.juxtapose161.myalarm.quiz.TranslateSession
import io.github.juxtapose161.myalarm.quiz.Word
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Outlives the activity: saving results after the alarm is off. */
val AppScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** What a task screen can use. */
interface TaskHost {
    val preview: Boolean
    fun speak(text: String, rate: Float = 0.85f)
    fun stopSpeaking()
    /** Tells Sleep the user is still busy with the captcha (the alarm stays quiet). */
    fun alive()
    /** The step is over; the next one starts (or the alarm stops). */
    fun complete(result: StepResult)
}

/**
 * The screen Sleep as Android opens when the alarm rings.
 * The scenario (by time of day) decides the tasks: warm-up cards, then the main task(s).
 * Tasks prepared by Claude come from the synced stock; without one, translation or cards are used,
 * so the alarm can always be stopped without network.
 * solved() stops the alarm; unsolved() (back button) returns to the ringing alarm.
 */
class CaptchaActivity : ComponentActivity(), TaskHost {

    private lateinit var captcha: CaptchaSupport
    private var speaker: Speaker? = null
    private var done = false

    private val screen = mutableStateOf<CaptchaUi>(CaptchaUi.Loading("Готовлю задания…"))
    private val remaining = mutableStateOf<Int?>(null)
    private val escapeVisible = mutableStateOf(false)

    private val timeListener = RemainingTimeListener { seconds, _ -> remaining.value = seconds }
    private val startedAt = SystemClock.elapsedRealtime()
    private var stepStartedAt = SystemClock.elapsedRealtime()

    private lateinit var store: SettingsStore
    private lateinit var course: CourseStore
    private lateinit var scenario: Scenario
    private var words: List<Word> = emptyList()
    private var difficulty = 3
    private var steps: MutableList<Step> = mutableListOf()
    private var stepIndex = 0
    private var generation = 0

    override val preview get() = !captcha.isOperationalMode || intent.hasExtra(EXTRA_TEST_DIFFICULTY)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captcha = CaptchaSupportFactory.create(this).setRemainingTimeListener(timeListener)

        onBackPressedDispatcher.addCallback(this) {
            captcha.unsolved()
            finish()
        }

        setContent {
            AppTheme {
                CaptchaScreen(
                    ui = screen.value,
                    remainingSeconds = remaining.value,
                    escapeVisible = escapeVisible.value,
                    host = this,
                    onEscape = ::escape,
                )
            }
        }

        lifecycleScope.launch { prepare() }
        lifecycleScope.launch { watchEscape() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captcha = CaptchaSupportFactory.create(this, intent).setRemainingTimeListener(timeListener)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        captcha.alive()
    }

    override fun onDestroy() {
        super.onDestroy()
        speaker?.shutdown()
        captcha.destroy()
    }

    // --- TaskHost ---

    override fun speak(text: String, rate: Float) { speaker?.speak(text, rate) }
    override fun stopSpeaking() { speaker?.stop() }
    override fun alive() { captcha.alive() }

    override fun complete(result: StepResult) {
        val ui = screen.value as? CaptchaUi.Run ?: return
        saveResult(ui.step, result)
        if (!result.passed) {
            // A failed task is replaced by an offline one: the alarm must always be stoppable.
            val sub = offlineStep()
            steps[stepIndex] = sub
            show("«${ui.step.title}» не засчитано — вместо него: ${sub.kind.title}")
            return
        }
        stepIndex++
        if (stepIndex >= steps.size) onSolved() else show(null)
    }

    // --- flow ---

    private suspend fun prepare() {
        store = SettingsStore(this)
        course = CourseStore(this)
        val settings = store.load()
        val repo = AnkiRepository(this)

        val testDifficulty = intent.getIntExtra(EXTRA_TEST_DIFFICULTY, 0)
        difficulty = (if (testDifficulty in 1..5) testDifficulty else captcha.difficulty).coerceIn(1, 5)

        val ttsReady = CompletableDeferred<Boolean>()
        speaker = Speaker(this) { ttsReady.complete(it) }

        words = withContext(Dispatchers.IO) {
            if (repo.isReady()) repo.loadWords(settings, store.mistakes) else emptyList()
        }
        withTimeoutOrNull(3000) { ttsReady.await() }

        val testKind = intent.getStringExtra(EXTRA_TEST_KIND)?.let { TaskKind.byName(it) }
        val testScenario = intent.getStringExtra(EXTRA_TEST_SCENARIO)
        val scenarios = course.scenarios
        scenario = scenarios.firstOrNull { it.id == testScenario }
            ?: Scenario.pick(scenarios, Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) })

        var notice: String? = null
        val list = mutableListOf<Step>()
        if (testKind == null && scenario.warmupCards > 0) list += quick(scenario.warmupCards, warmup = true)
        val history = course.history.map { it.kind }.toMutableList()
        val fallback = if (words.size >= 2) TaskKind.TRANSLATE else TaskKind.QUICK
        val count = if (testKind != null) 1 else scenario.tasks.coerceAtLeast(1)
        repeat(count) {
            val kind = testKind ?: Scenario.chooseKind(scenario.kindList, history, ::available, fallback)
            history += kind.name
            val step = withContext(Dispatchers.IO) { build(kind) }
            if (step == null) {
                notice = if (kind.prepared) "Нет готового задания «${kind.title}» — нужна синхронизация. Вместо него: ${fallback.title}"
                else "Нет слов из Anki — вместо перевода карточки"
                list += offlineStep()
            } else list += step
        }
        steps = list
        show(notice)
    }

    private fun available(kind: TaskKind): Boolean = when (kind.engine) {
        Engine.QUICK -> true
        Engine.TRANSLATE -> words.size >= 2
        Engine.PREPARED, Engine.DICTATION -> (course.stock()[kind] ?: 0) > 0
    }

    private fun build(kind: TaskKind): Step? = when (kind.engine) {
        Engine.QUICK -> quick(TaskGenerator.requiredFor(difficulty), warmup = false)
        Engine.TRANSLATE -> if (words.size >= 2) translate() else null
        Engine.PREPARED -> course.takeTask(kind)?.let { Step.Prepared(kind, it) }
        Engine.DICTATION -> course.takeTask(kind)?.let { t ->
            Dictation(t).takeIf { it.gaps.isNotEmpty() }?.let { Step.Dictate(t, it) }
        }
    }

    private fun show(notice: String?) {
        generation++
        stepStartedAt = SystemClock.elapsedRealtime()
        screen.value = CaptchaUi.Run(
            step = steps[stepIndex],
            index = stepIndex,
            total = steps.size,
            notice = notice,
            scenario = scenario.name,
            preview = preview,
            generation = generation,
        )
        captcha.alive()
    }

    private fun quick(required: Int, warmup: Boolean): Step.Quick {
        var types = store.load().types
        if (speaker?.ready != true) types = types - TaskType.AUDIO
        return Step.Quick(QuizSession(TaskGenerator(words, types, difficulty), required), warmup)
    }

    private fun translate() =
        // Mistakes and due cards first (the order from Anki), the rest mixed.
        Step.Translate(TranslateSession(words.take(10) + words.drop(10).shuffled(), course.params.translateWords))

    /** Translation if there are words, otherwise cards with math. Works without network. */
    private fun offlineStep(): Step =
        if (words.size >= 2) translate()
        else Step.Quick(QuizSession(TaskGenerator(words, setOf(TaskType.MATH, TaskType.CHOICE, TaskType.TYPING), difficulty),
            TaskGenerator.requiredFor(difficulty)), warmup = false)

    private suspend fun watchEscape() {
        while (true) {
            delay(10_000)
            if (!::scenario.isInitialized || scenario.escapeMinutes <= 0) continue
            val minutes = (SystemClock.elapsedRealtime() - startedAt) / 60_000
            if (minutes >= scenario.escapeMinutes) { escapeVisible.value = true; return }
        }
    }

    /** "No time": math instead of the rest, like a plain alarm. */
    private fun escape() {
        if (screen.value !is CaptchaUi.Run) return
        escapeVisible.value = false
        if (!preview) course.addHistory(LocalHistory(today(), "ESCAPE", scenario.name, false))
        steps = (steps.take(stepIndex) + Step.Quick(
            QuizSession(TaskGenerator(emptyList(), setOf(TaskType.MATH), difficulty), TaskGenerator.requiredFor(difficulty)),
            warmup = false,
        )).toMutableList()
        show("Запасной вариант: примеры вместо заданий")
    }

    private fun onSolved() {
        if (done) return
        done = true
        if (!preview) store.addHistory("${today()}|$solvedCorrect|$solvedMissed")
        Sync.soon(applicationContext)
        captcha.solved()
        finish()
    }

    private var solvedCorrect = 0
    private var solvedMissed = 0

    private fun saveResult(step: Step, r: StepResult) {
        solvedCorrect += r.correctNotes.size
        solvedMissed += r.missedNotes.size
        if (!preview && (r.missedNotes.isNotEmpty() || r.correctNotes.isNotEmpty())) {
            val learned = r.correctNotes - r.missedNotes
            store.mistakes = (store.mistakes - learned) + r.missedNotes
            if (store.load().tagMistakesInAnki && r.missedNotes.isNotEmpty()) {
                val missed = r.missedNotes.toList()
                val repo = AnkiRepository(applicationContext)
                AppScope.launch { repo.tagMistakes(missed) }
            }
        }
        if (step is Step.Quick && step.warmup) return
        if (!preview) course.addHistory(LocalHistory(today(), step.kind.name, step.title, r.passed))

        // Everything except the warm-up goes to Claude for the review and the profile.
        val missedWords = words.filter { it.noteId in r.missedNotes }.map { "${it.main} — ${it.ru}" }
        val correctWords = words.filter { it.noteId in r.correctNotes }.map { it.main }
        val base = r.answer ?: AnswerFile(taskId = "", kind = step.kind.name, answeredAt = "", passed = r.passed)
        val answer = base.copy(
            taskId = when (step) {
                is Step.Prepared -> step.task.id
                is Step.Dictate -> step.task.id
                else -> Sync.localId(step.kind.name)
            },
            kind = step.kind.name,
            title = step.title,
            answeredAt = Sync.now(),
            scenario = scenario.name,
            passed = r.passed,
            seconds = (SystemClock.elapsedRealtime() - stepStartedAt) / 1000,
            missedWords = base.missedWords + missedWords,
            correctWords = base.correctWords + correctWords,
            preview = preview,
        )
        if (course.sync.enabled) course.queueAnswer(answer)
    }

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    companion object {
        const val EXTRA_TEST_DIFFICULTY = "test_difficulty"
        const val EXTRA_TEST_KIND = "test_kind"
        const val EXTRA_TEST_SCENARIO = "test_scenario"
    }
}

sealed class CaptchaUi {
    data class Loading(val message: String) : CaptchaUi()
    data class Run(
        val step: Step,
        val index: Int,
        val total: Int,
        val notice: String?,
        val scenario: String,
        val preview: Boolean,
        /** Changes whenever a new step is shown, to reset the step's UI state. */
        val generation: Int,
    ) : CaptchaUi()
}
