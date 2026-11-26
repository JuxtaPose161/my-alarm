package io.github.juxtapose161.myalarm.ui

import android.content.Intent
import android.os.Bundle
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
import io.github.juxtapose161.myalarm.quiz.QuizSession
import io.github.juxtapose161.myalarm.quiz.TaskGenerator
import io.github.juxtapose161.myalarm.quiz.TaskType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The screen Sleep as Android opens when the alarm rings.
 * solved() stops the alarm; unsolved() (back button) returns to the ringing alarm.
 */
class CaptchaActivity : ComponentActivity() {

    private lateinit var captcha: CaptchaSupport
    private var speaker: Speaker? = null
    private var done = false

    private val screen = mutableStateOf<CaptchaUi>(CaptchaUi.Loading)
    private val remaining = mutableStateOf<Int?>(null)

    private val timeListener = RemainingTimeListener { seconds, _ -> remaining.value = seconds }

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
                    speak = { speaker?.speak(it) },
                    onSolved = ::onSolved,
                )
            }
        }

        lifecycleScope.launch { prepare() }
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

    private suspend fun prepare() {
        val store = SettingsStore(this)
        val settings = store.load()
        val repo = AnkiRepository(this)

        val testDifficulty = intent.getIntExtra(EXTRA_TEST_DIFFICULTY, 0)
        val difficulty = (if (testDifficulty in 1..5) testDifficulty else captcha.difficulty).coerceIn(1, 5)

        var types = settings.types
        val ttsReady = CompletableDeferred<Boolean>()
        if (TaskType.AUDIO in types) speaker = Speaker(this) { ttsReady.complete(it) } else ttsReady.complete(false)

        val words = withContext(Dispatchers.IO) {
            if (repo.isReady()) repo.loadWords(settings, store.mistakes) else emptyList()
        }
        val tts = withTimeoutOrNull(3000) { ttsReady.await() } ?: false
        if (!tts) types = types - TaskType.AUDIO

        val generator = TaskGenerator(words, types, difficulty)
        val notice = when {
            generator.hasWords -> null
            !repo.isInstalled() -> "AnkiDroid не найден — сегодня только примеры"
            !repo.hasPermission() -> "Нет доступа к Anki — открой настройки плагина"
            else -> "В колоде не нашлось слов — сегодня только примеры"
        }
        screen.value = CaptchaUi.Quiz(
            session = QuizSession(generator, TaskGenerator.requiredFor(difficulty)),
            difficulty = difficulty,
            notice = notice,
            preview = !captcha.isOperationalMode,
        )
    }

    private fun onSolved() {
        if (done) return
        done = true
        val quiz = screen.value as? CaptchaUi.Quiz
        if (quiz != null && !quiz.preview) saveResults(quiz.session)
        captcha.solved()
        finish()
    }

    private fun saveResults(session: QuizSession) {
        val store = SettingsStore(this)
        val settings = store.load()
        val learned = session.correct - session.missed
        store.mistakes = (store.mistakes - learned) + session.missed
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        store.addHistory("$day|${session.correct.size}|${session.missed.size}")
        if (settings.tagMistakesInAnki && session.missed.isNotEmpty()) {
            val missed = session.missed.toList()
            val repo = AnkiRepository(applicationContext)
            Thread { repo.tagMistakes(missed) }.start()
        }
    }

    companion object {
        const val EXTRA_TEST_DIFFICULTY = "test_difficulty"
    }
}

sealed class CaptchaUi {
    object Loading : CaptchaUi()
    data class Quiz(
        val session: QuizSession,
        val difficulty: Int,
        val notice: String?,
        val preview: Boolean,
    ) : CaptchaUi()
}
