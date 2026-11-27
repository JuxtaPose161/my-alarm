package io.github.juxtapose161.myalarm.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.juxtapose161.myalarm.course.AnswerFile
import io.github.juxtapose161.myalarm.course.AnswerMode
import io.github.juxtapose161.myalarm.course.Criterion
import io.github.juxtapose161.myalarm.course.Dictation
import io.github.juxtapose161.myalarm.course.LocalCheck
import io.github.juxtapose161.myalarm.quiz.TranslateSession
import kotlinx.coroutines.delay

private val Muted = Color.Gray
private val CardBg = Color(0xFF1B2229)

@Composable
private fun Hint(text: String) = Text(text, color = Muted)

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CardBg), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

/** A multi-line field for typed or voice-typed answers (keyboard microphone). */
@Composable
private fun AnswerField(value: String, onChange: (String) -> Unit, enabled: Boolean, label: String, minLines: Int = 5) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        label = { Text(label) },
        minLines = minLines,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth(),
    )
}

// ---------------------------------------------------------------------------------------------
// Task 1: translation
// ---------------------------------------------------------------------------------------------

@Composable
fun TranslateStep(session: TranslateSession, host: TaskHost) {
    var card by remember { mutableStateOf(session.current) }
    var input by remember { mutableStateOf("") }
    var verdict by remember { mutableStateOf<Boolean?>(null) }
    var done by remember { mutableIntStateOf(session.done) }
    val disputed = remember { mutableStateListOf<String>() }
    val focus = remember { FocusRequester() }

    LaunchedEffect(card) { runCatching { focus.requestFocus() } }
    LaunchedEffect(verdict) {
        val v = verdict ?: return@LaunchedEffect
        delay(if (v) 800 else 2600)
        session.answer(v)
        done = session.done
        if (session.isFinished) {
            host.complete(StepResult(
                true,
                missedNotes = session.missed.toSet(),
                correctNotes = session.correct.toSet(),
                answer = AnswerFile(taskId = "", kind = "", answeredAt = "", passed = true, disputed = disputed.toList()),
            ))
            return@LaunchedEffect
        }
        card = session.current
        input = ""; verdict = null
    }

    val c = card ?: return
    fun check() {
        if (verdict != null || input.isBlank()) return
        val ok = session.checkLocally(input)
        // Answers that are not on the card go to Claude: maybe it is a fine synonym.
        if (!ok) disputed += "${c.prompt} → «${input.trim()}» (на карточке: ${c.shownAnswer})"
        verdict = ok
    }

    Text("✓ $done / ${session.required}", color = Muted)
    LinearProgressIndicator(progress = { done.toFloat() / session.required }, modifier = Modifier.fillMaxWidth())
    Hint(if (c.toEnglish) "Переведи на английский (RU → EN)" else "Переведи на русский (EN → RU)")
    Text(c.prompt, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
    OutlinedTextField(
        value = input,
        onValueChange = { input = it; host.alive() },
        singleLine = true,
        enabled = verdict == null,
        label = { Text("Напиши или продиктуй (микрофон на клавиатуре)") },
        textStyle = MaterialTheme.typography.headlineSmall,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { check() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    Button(onClick = ::check, enabled = verdict == null, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text("Проверить", fontSize = 18.sp)
    }
    when (verdict) {
        true -> Text("Верно!", color = Good, fontSize = 18.sp)
        false -> {
            Text("Не засчитано, карточка пропущена", color = Bad, fontSize = 18.sp)
            Text("${c.word.en} — ${c.word.ru}", color = Warn, fontSize = 18.sp)
            Hint("Если твой вариант тоже верный, Claude учтёт это при разборе.")
        }
        null -> Hint("Можно написать один из переводов. Ошибка — карточка пропускается и вернётся позже.")
    }
}

// ---------------------------------------------------------------------------------------------
// Tasks prepared by Claude
// ---------------------------------------------------------------------------------------------

@Composable
fun CriteriaView(criteria: List<Criterion>, passed: Boolean) {
    Panel {
        Text(if (passed) "Засчитано ✓" else "Пока не засчитано", color = if (passed) Good else Bad,
            fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        criteria.forEach { c ->
            Text("${if (c.ok) "✓" else "✗"} ${c.name}${if (c.comment.isNotBlank()) " — ${c.comment}" else ""}",
                color = if (c.ok) Color.White else Warn)
        }
        Hint("Телефон проверяет по критериям задания. Подробный разбор с исправлениями Claude пришлёт позже (вкладка «Прогресс»).")
    }
}

@Composable
fun PreparedStep(step: Step.Prepared, host: TaskHost) {
    val task = step.task
    val mode = step.kind.mode
    var prepLeft by remember { mutableIntStateOf(task.prepSeconds) }
    var materialHidden by remember { mutableStateOf(false) }
    var main by remember { mutableStateOf("") }
    val answers = remember { mutableStateListOf(*Array(task.items.size) { "" }) }
    var revealed by remember { mutableIntStateOf(if (mode == AnswerMode.DIALOG) 1 else task.items.size) }
    var result by remember { mutableStateOf<LocalCheck.Result?>(null) }
    var attempts by remember { mutableIntStateOf(0) }
    val startedAt = remember { SystemClock.elapsedRealtime() }
    var elapsed by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        if (mode == AnswerMode.DIALOG) task.items.firstOrNull()?.let { host.speak(it.text) }
        while (true) {
            delay(1000)
            elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1000
            if (prepLeft > 0) { prepLeft--; host.alive() }
        }
    }

    val passed = result?.passed == true
    fun answerFile() = AnswerFile(
        taskId = "", kind = "", answeredAt = "", main = main, items = answers.toList(),
        criteria = result?.criteria.orEmpty(), passed = passed, attempts = attempts,
    )
    fun check() {
        attempts++
        result = LocalCheck.check(task, main, answers.toList(), elapsed)
    }
    fun edit(change: () -> Unit) { change(); host.alive(); if (result?.passed == false) result = null }

    Hint(task.instructionsRu)
    if (task.material.isNotBlank()) {
        if (!materialHidden) Panel {
            Text(task.material, fontSize = 17.sp, lineHeight = 24.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (task.speakMaterial) OutlinedButton(onClick = { host.speak(task.material) }) { Text("🔊 Прослушать") }
                if (task.hideMaterial) Button(onClick = { host.stopSpeaking(); materialHidden = true }) { Text("Прочитал(а)") }
            }
        } else if (!passed) {
            OutlinedButton(onClick = { materialHidden = false }) { Text("Показать текст ещё раз") }
        }
    }
    if (task.words.isNotEmpty()) Text("Слова: ${task.words.joinToString(" · ") { it.word }}", color = Warn)
    if (task.grammar.isNotEmpty()) Text("Грамматика: ${task.grammar.joinToString(" · ") { it.hintRu.ifBlank { it.id } }}", color = Warn)
    if (task.minMinutes > 0) {
        val left = (task.minMinutes * 60L - elapsed).coerceAtLeast(0)
        Text(if (left == 0L) "✓ ${task.minMinutes} мин" else "⏳ Ещё %d:%02d".format(left / 60, left % 60), color = Muted)
    }

    if (prepLeft > 0) {
        if (task.items.isNotEmpty() && mode == AnswerMode.SINGLE) Panel { task.items.forEachIndexed { i, it -> Text("${i + 1}. ${it.text}") } }
        Text("Подготовка: $prepLeft с", fontSize = 22.sp)
        OutlinedButton(onClick = { prepLeft = 0 }) { Text("Начать сейчас") }
        return
    }
    // For a retell the text must be hidden before answering.
    if (task.hideMaterial && !materialHidden && task.material.isNotBlank() && result == null) {
        Hint("Когда прочитаешь, нажми «Прочитал(а)» — текст скроется, и можно будет отвечать.")
        return
    }

    when (mode) {
        AnswerMode.SINGLE -> {
            if (task.items.isNotEmpty()) Panel { task.items.forEachIndexed { i, it -> Text("${i + 1}. ${it.text}") } }
            AnswerField(main, { v -> edit { main = v } }, enabled = !passed, label = "Ответ (можно голосом с клавиатуры)", minLines = 6)
        }
        AnswerMode.PER_ITEM, AnswerMode.MAIN_AND_ITEMS -> {
            if (mode == AnswerMode.MAIN_AND_ITEMS) {
                AnswerField(main, { v -> edit { main = v } }, enabled = !passed, label = "Пересказ (можно голосом)", minLines = 5)
            }
            task.items.forEachIndexed { i, item ->
                Text("${i + 1}. ${item.text}", fontWeight = FontWeight.SemiBold)
                AnswerField(answers[i], { v -> edit { answers[i] = v } }, enabled = !passed, label = "Ответ ${i + 1}", minLines = 2)
            }
        }
        AnswerMode.DIALOG -> {
            task.items.take(revealed).forEachIndexed { i, item ->
                Text(item.text, fontSize = 16.sp, modifier = Modifier.widthIn(max = 320.dp)
                    .background(CardBg, RoundedCornerShape(12.dp)).padding(10.dp))
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                    AnswerField(answers[i], { v -> edit { answers[i] = v } }, enabled = !passed, label = "Твоя реплика", minLines = 2)
                }
            }
            if (revealed < task.items.size) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        revealed++
                        host.speak(task.items[revealed - 1].text)
                    }, enabled = answers[revealed - 1].isNotBlank(), modifier = Modifier.weight(1f).height(52.dp)) { Text("Ответить") }
                    OutlinedButton(onClick = { host.speak(task.items[revealed - 1].text) }, modifier = Modifier.height(52.dp)) { Text("🔊") }
                }
            }
        }
    }
    val words = LocalCheck.wordCount(if (mode == AnswerMode.DIALOG) answers.joinToString(" ") else main)
    if (task.minWords > 0) Text("Слов: $words / ${task.minWords}", color = if (words >= task.minWords) Good else Muted)

    val ready = when (mode) {
        AnswerMode.SINGLE -> main.isNotBlank()
        AnswerMode.MAIN_AND_ITEMS -> main.isNotBlank()
        AnswerMode.PER_ITEM -> answers.any { it.isNotBlank() }
        AnswerMode.DIALOG -> revealed >= task.items.size && answers.last().isNotBlank()
    }
    if (!passed) Button(onClick = ::check, enabled = ready, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text(if (attempts == 0) "Проверить" else "Проверить снова", fontSize = 17.sp)
    }
    result?.let { CriteriaView(it.criteria, it.passed) }
    if (passed) Button(onClick = { host.complete(StepResult(true, answer = answerFile())) },
        modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Готово ✓", fontSize = 18.sp) }
    // If the criteria turn out impossible to meet, the answer still goes to Claude and an offline task follows.
    if (!passed && attempts >= 3) OutlinedButton(onClick = { host.complete(StepResult(false, answer = answerFile())) },
        modifier = Modifier.fillMaxWidth()) { Text("Не получается — отправить на разбор и перейти к переводу слов") }
}

// ---------------------------------------------------------------------------------------------
// Dictation with gaps
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DictationStep(step: Step.Dictate, host: TaskHost) {
    val d: Dictation = step.dictation
    val inputs = remember { mutableStateListOf(*Array(d.gaps.size) { "" }) }
    var wrong by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var checks by remember { mutableIntStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    var passed by remember { mutableStateOf(false) }
    var slow by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { delay(600); host.speak(d.spoken) }

    fun result(ok: Boolean) = StepResult(ok, answer = AnswerFile(
        taskId = "", kind = "", answeredAt = "", passed = ok, attempts = checks,
        items = inputs.toList(),
        missedWords = d.gaps.filter { it.index in wrong }.map { "${it.answer} (написано: «${inputs[it.index]}»)" },
        correctWords = d.gaps.filter { it.index !in wrong }.map { it.answer },
    ))

    Hint("Послушай и впиши пропущенные слова. Можно слушать сколько угодно раз.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { host.speak(d.spoken, if (slow) 0.6f else 0.85f) }) { Text("▶ Прослушать") }
        OutlinedButton(onClick = { slow = !slow }) { Text(if (slow) "Медленно ✓" else "Медленно") }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        d.parts.forEach { part ->
            when (part) {
                is Dictation.Part.Text -> part.text.split(' ').filter { it.isNotEmpty() }.forEach { Text(it, fontSize = 17.sp) }
                is Dictation.Part.Gap -> {
                    val i = part.index
                    val bad = i in wrong
                    BasicTextField(
                        value = if (revealed && bad) part.answer else inputs[i],
                        onValueChange = { v -> if (!revealed && !passed) { inputs[i] = v; wrong = wrong - i; host.alive() } },
                        singleLine = true,
                        textStyle = TextStyle(color = if (bad) Bad else Color.White, fontSize = 17.sp),
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                        modifier = Modifier
                            .width((part.answer.length.coerceIn(4, 14) * 11 + 16).dp)
                            .background(if (bad) Color(0xFF3A1A1A) else Color(0xFF26323C), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
    if (!passed && !revealed) Button(onClick = {
        val w = d.check(inputs.toList()).toSet()
        wrong = w; checks++
        when {
            w.size <= d.allowedMistakes -> passed = true
            checks >= 3 -> revealed = true
        }
    }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Проверить ($checks/3)") }
    if (wrong.isNotEmpty() && !passed && !revealed) Text("Ошибок: ${wrong.size}, можно ${d.allowedMistakes}. Красные поля — исправь.", color = Warn)
    if (passed) {
        Text("Засчитано ✓${if (wrong.isNotEmpty()) " (ошибок: ${wrong.size})" else ""}", color = Good)
        if (wrong.isNotEmpty()) Text(d.gaps.filter { it.index in wrong }.joinToString { it.answer }, color = Warn)
        Button(onClick = { host.complete(result(true)) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Готово ✓") }
    }
    if (revealed) {
        Text("Три попытки кончились — ответы показаны красным.", color = Bad)
        Button(onClick = { host.complete(result(false)) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Дальше") }
    }
}
