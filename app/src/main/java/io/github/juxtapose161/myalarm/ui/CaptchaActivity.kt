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
import io.github.juxtapose161.myalarm.course.CourseStore
import io.github.juxtapose161.myalarm.course.Dictation
import io.github.juxtapose161.myalarm.course.Engine
import io.github.juxtapose161.myalarm.course.LearnerProfile
import io.github.juxtapose161.myalarm.course.TaskParams
import io.github.juxtapose161.myalarm.course.Evaluation
import io.github.juxtapose161.myalarm.course.PreparedTask
import io.github.juxtapose161.myalarm.course.Preparer
import io.github.juxtapose161.myalarm.course.Scenario
import io.github.juxtapose161.myalarm.course.TaskKind
import io.github.juxtapose161.myalarm.course.Tutor
import io.github.juxtapose161.myalarm.llm.ClaudeClient
import io.github.juxtapose161.myalarm.quiz.QuizSession
import io.github.juxtapose161.myalarm.quiz.TaskGenerator
import io.github.juxtapose161.myalarm.quiz.TaskType
import io.github.juxtapose161.myalarm.quiz.TranslateSession
import io.github.juxtapose161.myalarm.quiz.Word
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Outlives the activity: saving results and talking to Claude after the alarm is off. */
val AppScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** What a task screen can use. */
interface TaskHost {
    val tutor: Tutor?
    val preview: Boolean
    val checkSynonyms: Boolean
    fun speak(text: String, rate: Float = 0.85f)
    fun stopSpeaking()
    /** Tells Sleep the user is still busy with the captcha (the alarm stays quiet). */
    fun alive()
    /** Runs a slow call (Claude) while keeping the captcha alive. */
    suspend fun <T> busy(block: suspend () -> T): T
    /** Saves a task checked by Claude to the learner profile. */
    fun record(kind: TaskKind, title: String, evaluation: Evaluation)
    /** The step is over; the next one starts (or the alarm stops). */
    fun complete(result: StepResult)
}

/**
 * The screen Sleep as Android opens when the alarm rings.
 * The scenario (by time of day) decides the tasks: warm-up cards, then the main task(s).
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

    // Filled in prepare().
    private lateinit var store: SettingsStore
    private lateinit var course: CourseStore
    private lateinit var scenario: Scenario
    private var words: List<Word> = emptyList()
    private var difficulty = 3
    private var claudeTutor: Tutor? = null
    private var synonyms = false
    private var planned: List<Deferred<Pair<Step, String?>>> = emptyList()
    private var stepIndex = 0

    override val tutor get() = claudeTutor
    override val preview get() = !captcha.isOperationalMode || intent.hasExtra(EXTRA_TEST_DIFFICULTY)
    override val checkSynonyms get() = synonyms

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

    override suspend fun <T> busy(block: suspend () -> T): T = coroutineScope {
        val ticker = launch {
            while (isActive) {
                captcha.alive()
                delay(15_000)
            }
        }
        try { block() } finally { ticker.cancel() }
    }

    override fun record(kind: TaskKind, title: String, evaluation: Evaluation) {
        if (preview) return
        val t = claudeTutor ?: return
        AppScope.launch { runCatching { t.record(kind, title, evaluation) } }
    }

    override fun complete(result: StepResult) {
        val ui = screen.value as? CaptchaUi.Run ?: return
        saveResult(ui.step, result)
        if (!result.passed) {
            // A failed task is replaced by an offline one: the alarm must always be stoppable.
            lifecycleScope.launch {
                val sub = offlineStep(ui.step.kind)
                show(sub, "«${ui.step.kind.title}» не засчитано — вместо него: ${sub.kind.title}", sameIndex = true)
            }
            return
        }
        stepIndex++
        if (stepIndex >= planned.size) onSolved() else lifecycleScope.launch { showPlanned() }
    }

    // --- flow ---

    private suspend fun prepare() {
        store = SettingsStore(this)
        course = CourseStore(this)
        val settings = store.load()
        val repo = AnkiRepository(this)
        val claude = course.claude
        synonyms = claude.checkSynonyms && claude.enabled
        if (claude.enabled) claudeTutor = Tutor(ClaudeClient(claude), course)

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

        val kinds = mutableListOf<TaskKind>()
        val warmup = if (testKind == null) scenario.warmupCards else 0
        val history = course.profile.history.toMutableList()
        if (testKind != null) kinds += testKind else repeat(scenario.tasks.coerceAtLeast(1)) {
            val k = Scenario.chooseKind(scenario.kindList, history, claude.enabled, words.isNotEmpty())
            kinds += k
            history += LearnerProfile.HistoryEntry("", k.name, "", true)
        }

        // Main tasks are resolved in the background while the warm-up runs.
        val list = mutableListOf<Deferred<Pair<Step, String?>>>()
        if (warmup > 0) list += CompletableDeferred(quick(warmup, warmup = true) to null)
        kinds.forEach { k -> list += lifecycleScope.async { resolve(k) } }
        planned = list
        showPlanned()
    }

    private suspend fun showPlanned() {
        val deferred = planned[stepIndex]
        if (!deferred.isCompleted) screen.value = CaptchaUi.Loading("Claude готовит задание…")
        val (step, notice) = busy { deferred.await() }
        show(step, notice)
    }

    private fun show(step: Step, notice: String?, sameIndex: Boolean = false) {
        val prev = screen.value as? CaptchaUi.Run
        screen.value = CaptchaUi.Run(
            step = step,
            index = stepIndex,
            total = planned.size,
            notice = notice,
            scenario = scenario.name,
            preview = preview,
            generation = (prev?.generation ?: 0) + 1,
        )
        if (!sameIndex) captcha.alive()
    }

    /** Builds the step for [kind]: a prepared task, a freshly generated one or an offline substitute. */
    private suspend fun resolve(kind: TaskKind): Pair<Step, String?> {
        val params = course.params
        when (kind.engine) {
            Engine.QUICK -> return quick(TaskGenerator.requiredFor(difficulty), warmup = false) to null
            Engine.TRANSLATE -> return offlineStep(kind) to null
            Engine.ALIAS -> {
                val pool = words.filter { it.main.length in 3..24 }
                return if (claudeTutor != null && pool.size >= params.aliasWords) {
                    Step.Alias(pool.take(30), params.aliasWords) to null
                } else offlineStep(kind) to "Для «${kind.title}» нужны Claude и слова из Anki — вместо него перевод слов"
            }
            else -> Unit
        }
        val t = claudeTutor
        // Answers to written tasks and role-plays are checked by Claude, so they need a key; a dictation does not.
        if (t == null && kind.engine != Engine.DICTATION) {
            return offlineStep(kind) to "Для «${kind.title}» нужен ключ Claude — вместо него задание без сети"
        }
        val level = course.profile.level
        var task: PreparedTask? = withContext(Dispatchers.IO) { course.takePrepared(kind, level) }
        if (task == null && t != null) {
            task = withTimeoutOrNull(75_000) {
                runCatching { t.prepare(kind, words.map { it.main }.take(40)) }.getOrNull()
            }
        }
        if (task != null) Preparer.runNow(applicationContext)
        val step = task?.let { toStep(kind, it, params) }
            ?: return offlineStep(kind) to "Claude сейчас недоступен — вместо «${kind.title}» задание без сети"
        return step to null
    }

    private fun toStep(kind: TaskKind, t: PreparedTask, p: TaskParams): Step? = when {
        t.written != null -> Step.Written(kind, t.written)
        t.chat != null -> Step.Chat(
            kind, t.chat,
            minutes = if (kind == TaskKind.DIALOG) p.dialogMinutes else 0,
            words = if (kind == TaskKind.DIALOG) minOf(p.dialogWords, t.chat.targetWords.size) else 0,
            grammar = if (kind == TaskKind.DIALOG) minOf(p.dialogGrammar, t.chat.targetGrammar.size) else 0,
        )
        t.dictation != null -> Dictation(t.dictation).takeIf { it.gaps.isNotEmpty() }?.let { Step.Dictate(it) }
        else -> null
    }

    private fun quick(required: Int, warmup: Boolean): Step.Quick {
        val settings = store.load()
        var types = settings.types
        if (speaker?.ready != true) types = types - TaskType.AUDIO
        return Step.Quick(QuizSession(TaskGenerator(words, types, difficulty), required), warmup)
    }

    /** Task 1 (translation) if there are words, otherwise cards with math. Works without network. */
    @Suppress("UNUSED_PARAMETER")
    private fun offlineStep(instead: TaskKind): Step {
        val n = course.params.translateWords
        return if (words.size >= 2) {
            // Mistakes and due cards first (the order from Anki), the rest mixed.
            Step.Translate(TranslateSession(words.take(10) + words.drop(10).shuffled(), n))
        } else {
            Step.Quick(QuizSession(TaskGenerator(words, setOf(TaskType.MATH, TaskType.CHOICE, TaskType.TYPING), difficulty),
                TaskGenerator.requiredFor(difficulty)), warmup = false)
        }
    }

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
        escapeVisible.value = false
        val math = Step.Quick(
            QuizSession(TaskGenerator(emptyList(), setOf(TaskType.MATH), difficulty), TaskGenerator.requiredFor(difficulty)),
            warmup = false,
        )
        if (!preview) course.updateProfile {
            it.copy(history = (it.history + LearnerProfile.HistoryEntry(
                Tutor.today(), "ESCAPE", scenario.name, false, "запасной выход")).takeLast(60))
        }
        // The remaining planned tasks are dropped.
        planned = planned.take(stepIndex + 1)
        show(math, "Запасной вариант: примеры вместо заданий", sameIndex = true)
    }

    private fun onSolved() {
        if (done) return
        done = true
        if (!preview) {
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            store.addHistory("$day|${solvedCorrect}|${solvedMissed}")
        }
        captcha.solved()
        finish()
    }

    private var solvedCorrect = 0
    private var solvedMissed = 0

    private fun saveResult(step: Step, r: StepResult) {
        solvedCorrect += r.correctNotes.size
        solvedMissed += r.missedNotes.size
        if (preview) return
        if (r.missedNotes.isNotEmpty() || r.correctNotes.isNotEmpty()) {
            val learned = r.correctNotes - r.missedNotes
            store.mistakes = (store.mistakes - learned) + r.missedNotes
            val settings = store.load()
            if (settings.tagMistakesInAnki && r.missedNotes.isNotEmpty()) {
                val missed = r.missedNotes.toList()
                val repo = AnkiRepository(applicationContext)
                AppScope.launch { repo.tagMistakes(missed) }
            }
        }
        // Tasks checked by Claude are recorded by their screens (with the full evaluation).
        if (step is Step.Written || step is Step.Chat) return
        if (step is Step.Quick && step.warmup) return
        val missedWords = r.missedWords.ifEmpty { words.filter { it.noteId in r.missedNotes }.map { it.main } }
        val usedWords = words.filter { it.noteId in r.correctNotes }.map { it.main }
        course.updateProfile {
            it.withResult(Tutor.today(), step.kind, step.title, r.passed, usedWords = usedWords, missedWords = missedWords)
        }
    }

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
