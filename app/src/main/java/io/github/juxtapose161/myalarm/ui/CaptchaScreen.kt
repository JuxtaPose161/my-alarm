package io.github.juxtapose161.myalarm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.juxtapose161.myalarm.quiz.QuizSession
import io.github.juxtapose161.myalarm.quiz.QuizTask
import io.github.juxtapose161.myalarm.quiz.TaskType
import io.github.juxtapose161.myalarm.quiz.TextUtil
import kotlinx.coroutines.delay

internal val Good = Color(0xFF2E7D32)
internal val Bad = Color(0xFFC62828)
internal val Warn = Color(0xFFFFB74D)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF101418)) { content() }
    }
}

@Composable
fun CaptchaScreen(
    ui: CaptchaUi,
    remainingSeconds: Int?,
    escapeVisible: Boolean,
    host: TaskHost,
    onEscape: () -> Unit,
) {
    when (ui) {
        is CaptchaUi.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator()
                Text(ui.message, color = Color.Gray)
            }
        }
        is CaptchaUi.Run -> Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (ui.total > 1) "${ui.step.title} · ${ui.index + 1}/${ui.total}" else ui.step.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (ui.preview) Text("проба · ", color = Color.Gray)
                remainingSeconds?.let { Text("⏱ $it с", color = if (it <= 10) Bad else Color.Gray) }
            }
            ui.notice?.let { Text(it, color = Warn) }
            // key() resets all per-task UI state when the step changes.
            key(ui.generation) {
                when (val step = ui.step) {
                    is Step.Quick -> QuizSteps(step.session, host) { host.complete(quickResult(step.session)) }
                    is Step.Translate -> TranslateStep(step.session, host)
                    is Step.Written -> WrittenStep(step, host)
                    is Step.Chat -> ChatStep(step, host)
                    is Step.Alias -> AliasStep(step, host)
                    is Step.Dictate -> DictationStep(step, host)
                }
            }
            if (escapeVisible) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onEscape, modifier = Modifier.fillMaxWidth()) {
                    Text("Не успеваю — решить примеры вместо заданий")
                }
            }
        }
    }
}

private fun quickResult(s: QuizSession) = StepResult(true, missedNotes = s.missed.toSet(), correctNotes = s.correct.toSet())

/** The quick cards: counter, progress and one card at a time. */
@Composable
private fun QuizSteps(session: QuizSession, host: TaskHost, onSolved: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var answers by remember { mutableIntStateOf(0) } // bumps the header after each answer
    val refresh: () -> Unit = { answers++ }
    val speak: (String) -> Unit = { host.speak(it) }
    val next: () -> Unit = {
        if (session.isFinished) onSolved() else {
            session.advance()
            step++
        }
    }

    val counter = answers.let { "✓ ${session.done} / ${session.required}" }
    Text(counter, color = Color.Gray)
    val progress = answers.let { (session.done.toFloat() / session.required).coerceIn(0f, 1f) }
    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())

    key(step) {
        when (val task = session.current) {
            is QuizTask.Choice -> ChoiceTask(task, session, speak, refresh, next)
            is QuizTask.Typing -> TypingTask(task, session, refresh, next)
            is QuizTask.Math -> MathTask(task, session, refresh, next)
        }
    }
}

@Composable
private fun ChoiceTask(
    task: QuizTask.Choice,
    session: QuizSession,
    speak: (String) -> Unit,
    refresh: () -> Unit,
    next: () -> Unit,
) {
    var picked by remember { mutableStateOf<Int?>(null) }
    var result by remember { mutableStateOf<QuizSession.Result.ChoiceResult?>(null) }

    if (task.speak != null) LaunchedEffect(Unit) { speak(task.speak) }
    LaunchedEffect(result) {
        val r = result ?: return@LaunchedEffect
        delay(if (r.correct) 700 else 2200)
        next()
    }

    Text(task.instruction, color = Color.Gray)
    if (task.type == TaskType.AUDIO) {
        OutlinedButton(onClick = { speak(task.speak!!) }, modifier = Modifier.fillMaxWidth().height(72.dp)) {
            Text("🔊  Повторить", fontSize = 22.sp)
        }
    } else {
        Text(
            task.prompt,
            fontSize = if (task.type == TaskType.CLOZE) 22.sp else 32.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        )
    }

    task.options.forEachIndexed { i, option ->
        val r = result
        val color = when {
            r == null -> MaterialTheme.colorScheme.primaryContainer
            i == r.correctIndex -> Good
            i == picked -> Bad
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
        Button(
            onClick = { if (result == null) { picked = i; result = session.answerChoice(i); refresh() } },
            colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text(option, fontSize = 18.sp, textAlign = TextAlign.Center) }
    }

    result?.takeIf { !it.correct }?.let {
        Text("${task.word.en} — ${task.word.ru}", fontSize = 18.sp, color = Color(0xFFFFB74D))
        task.word.example?.let { ex -> Text(ex, color = Color.Gray) }
    }
}

@Composable
private fun TypingTask(task: QuizTask.Typing, session: QuizSession, refresh: () -> Unit, next: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf(TextUtil.hint(task.word.main, showFirstLetter = false)) }
    var message by remember { mutableStateOf<String?>(null) }
    var finished by remember { mutableStateOf<Boolean?>(null) } // true = correct, false = revealed
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(finished) {
        val f = finished ?: return@LaunchedEffect
        delay(if (f) 700 else 2500)
        next()
    }

    fun check() {
        if (finished != null || input.isBlank()) return
        val r = session.answerTyping(input)
        refresh()
        when (r) {
            QuizSession.Result.TypingCorrect -> { message = "Верно!"; finished = true }
            is QuizSession.Result.TypingWrong -> { message = "Не то. Подсказка:"; hint = r.hint; input = "" }
            is QuizSession.Result.TypingRevealed -> { message = "Правильно: ${r.answer}"; finished = false }
            else -> Unit
        }
    }

    Text("Напиши по-английски", color = Color.Gray)
    Text(task.prompt, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
    Text(hint, fontSize = 22.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(), color = Color.Gray)
    OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        singleLine = true,
        enabled = finished == null,
        textStyle = MaterialTheme.typography.headlineSmall,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { check() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    Button(onClick = ::check, enabled = finished == null, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text("Проверить", fontSize = 18.sp)
    }
    message?.let {
        Text(it, fontSize = 18.sp, color = when (finished) { true -> Good; false -> Bad; null -> Color(0xFFFFB74D) })
    }
    if (finished == false) task.word.example?.let { Text(it, color = Color.Gray) }
}

@Composable
private fun MathTask(task: QuizTask.Math, session: QuizSession, refresh: () -> Unit, next: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var solved by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(solved) {
        if (!solved) return@LaunchedEffect
        delay(500)
        next()
    }

    fun check() {
        if (solved || input.isBlank()) return
        if (session.answerMath(input) == QuizSession.Result.MathCorrect) { solved = true; refresh() }
        else { wrong = true; input = "" }
    }

    Text("Реши пример", color = Color.Gray)
    Text("${task.expression} = ?", fontSize = 34.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
    OutlinedTextField(
        value = input,
        onValueChange = { v -> input = v.filter { it.isDigit() || it == '-' }; wrong = false },
        singleLine = true,
        enabled = !solved,
        textStyle = MaterialTheme.typography.headlineSmall,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { check() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    Button(onClick = ::check, enabled = !solved, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text("Проверить", fontSize = 18.sp)
    }
    if (wrong) Text("Неверно, попробуй ещё раз", color = Bad, fontSize = 18.sp)
    if (solved) Text("Верно!", color = Good, fontSize = 18.sp)
    Spacer(Modifier.height(8.dp))
}
