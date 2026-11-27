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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
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
import io.github.juxtapose161.myalarm.course.Curriculum
import io.github.juxtapose161.myalarm.course.Dictation
import io.github.juxtapose161.myalarm.course.Evaluation
import io.github.juxtapose161.myalarm.course.TaskKind
import io.github.juxtapose161.myalarm.quiz.TextUtil
import io.github.juxtapose161.myalarm.quiz.TranslateSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Muted = Color.Gray
private val CardBg = Color(0xFF1B2229)

private fun wordCount(s: String) = s.split(Regex("""\s+""")).count { it.any(Char::isLetter) }

private fun grammarNames(ids: List<String>) = ids.mapNotNull { Curriculum.grammar(it) }.map { it.ru }

private fun errorText(e: Throwable): String {
    val m = e.message.orEmpty()
    return when {
        "401" in m || "authentication" in m.lowercase() -> "Claude не принял ключ API — проверь его в настройках"
        "UnknownHost" in e.javaClass.simpleName || "Unable to resolve host" in m -> "Нет интернета"
        else -> "Ошибка Claude: ${m.take(140)}"
    }
}

@Composable
private fun Hint(text: String) = Text(text, color = Muted)

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CardBg), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

/** A large multi-line field for typed or voice-typed answers (keyboard microphone). */
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
    val scope = rememberCoroutineScope()
    var card by remember { mutableStateOf(session.current) }
    var input by remember { mutableStateOf("") }
    var verdict by remember { mutableStateOf<Boolean?>(null) }
    var comment by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(session.done) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(card) { runCatching { focus.requestFocus() } }
    LaunchedEffect(verdict) {
        val v = verdict ?: return@LaunchedEffect
        delay(if (v) 800 else 2600)
        session.answer(v)
        done = session.done
        if (session.isFinished) {
            host.complete(StepResult(true, missedNotes = session.missed.toSet(), correctNotes = session.correct.toSet()))
            return@LaunchedEffect
        }
        card = session.current
        input = ""; verdict = null; comment = null
    }

    val c = card ?: return
    fun check() {
        if (verdict != null || checking || input.isBlank()) return
        if (session.checkLocally(input)) { verdict = true; return }
        val tutor = host.tutor
        if (!host.checkSynonyms || tutor == null) { verdict = false; return }
        checking = true
        scope.launch {
            val r = runCatching { host.busy { tutor.synonym(c.toEnglish, c.prompt, c.shownAnswer, input) } }.getOrNull()
            checking = false
            comment = r?.comment?.takeIf { it.isNotBlank() }
            verdict = r?.acceptable ?: false
        }
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
        enabled = verdict == null && !checking,
        label = { Text("Напиши или продиктуй (микрофон на клавиатуре)") },
        textStyle = MaterialTheme.typography.headlineSmall,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { check() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    Button(onClick = ::check, enabled = verdict == null && !checking, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text(if (checking) "Проверяю синоним…" else "Проверить", fontSize = 18.sp)
    }
    when (verdict) {
        true -> Text("Верно! ${if (comment != null) "($comment)" else ""}", color = Good, fontSize = 18.sp)
        false -> {
            Text("Не засчитано, карточка пропущена", color = Bad, fontSize = 18.sp)
            Text("${c.word.en} — ${c.word.ru}", color = Warn, fontSize = 18.sp)
            comment?.let { Hint(it) }
        }
        null -> Hint("Можно написать один из переводов. Ошибка — карточка пропускается и вернётся позже.")
    }
}

// ---------------------------------------------------------------------------------------------
// Written tasks checked by Claude
// ---------------------------------------------------------------------------------------------

@Composable
fun WrittenStep(step: Step.Written, host: TaskHost) {
    val spec = step.spec
    val scope = rememberCoroutineScope()
    var prepLeft by remember { mutableIntStateOf(spec.prepSeconds) }
    var materialHidden by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Evaluation?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var failures by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (prepLeft > 0) { delay(1000); prepLeft--; host.alive() }
    }

    fun submit() {
        val tutor = host.tutor ?: return
        if (checking || answer.isBlank()) return
        checking = true; error = null
        scope.launch {
            val r = runCatching { host.busy { tutor.evaluate(step.kind, spec, answer) } }
            checking = false
            r.onSuccess { e ->
                result = e
                host.record(step.kind, spec.title, e)
            }.onFailure { error = errorText(it); failures++ }
        }
    }

    Hint(spec.instructionsRu)
    if (spec.material.isNotBlank()) {
        if (!materialHidden) Panel {
            Text(spec.material, fontSize = 17.sp, lineHeight = 24.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (spec.speakMaterial) OutlinedButton(onClick = { host.speak(spec.material) }) { Text("🔊 Прослушать") }
                if (spec.hideMaterial) Button(onClick = { host.stopSpeaking(); materialHidden = true }) { Text("Прочитал(а)") }
            }
        } else if (result?.passed != true) {
            OutlinedButton(onClick = { materialHidden = false }) { Text("Показать текст ещё раз") }
        }
    }
    if (spec.items.isNotEmpty()) Panel {
        spec.items.forEachIndexed { i, s -> Text("${i + 1}. $s", fontSize = 16.sp) }
    }
    if (spec.targetWords.isNotEmpty()) Text("Слова: ${spec.targetWords.joinToString(" · ")}", color = Warn)
    grammarNames(spec.targetGrammar).takeIf { it.isNotEmpty() }?.let { Text("Грамматика: ${it.joinToString(" · ")}", color = Warn) }

    if (prepLeft > 0) {
        Text("Подготовка: $prepLeft с", fontSize = 22.sp)
        OutlinedButton(onClick = { prepLeft = 0 }) { Text("Начать сейчас") }
        return
    }
    // For a retell the text must be hidden before answering.
    if (spec.hideMaterial && !materialHidden && spec.material.isNotBlank() && result == null) {
        Hint("Когда прочитаешь, нажми «Прочитал(а)» — текст скроется, и можно будет отвечать.")
        return
    }

    val passed = result?.passed == true
    AnswerField(answer, { answer = it; host.alive() }, enabled = !checking && !passed, label = "Ответ (можно голосом с клавиатуры)", minLines = 6)
    val words = wordCount(answer)
    if (spec.minWords > 0) Text("Слов: $words / ${spec.minWords}", color = if (words >= spec.minWords) Good else Muted)

    if (!passed) Button(onClick = ::submit, enabled = !checking && answer.isNotBlank(), modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text(if (checking) "Claude проверяет…" else if (result == null) "Проверить" else "Отправить исправленный ответ", fontSize = 17.sp)
    }
    if (checking) CircularProgressIndicator()
    error?.let { Text(it, color = Bad) }
    if (failures >= 2 && !passed) OutlinedButton(onClick = { host.complete(StepResult(false)) }, modifier = Modifier.fillMaxWidth()) {
        Text("Claude не отвечает — задание без сети")
    }
    result?.let { EvaluationView(it) }
    if (passed) Button(onClick = { host.complete(StepResult(true)) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text("Готово ✓", fontSize = 18.sp)
    }
}

@Composable
fun EvaluationView(e: Evaluation) {
    Panel {
        Text(if (e.passed) "Засчитано ✓" else "Пока не засчитано", color = if (e.passed) Good else Bad,
            fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        e.criteria.forEach { c ->
            Text("${if (c.ok) "✓" else "✗"} ${c.name}${if (c.comment.isNotBlank()) " — ${c.comment}" else ""}",
                color = if (c.ok) Color.White else Warn)
        }
        if (e.feedbackRu.isNotBlank()) Text(e.feedbackRu, color = Muted)
        if (e.corrections.isNotEmpty()) {
            Text("Исправления:", fontWeight = FontWeight.SemiBold)
            e.corrections.forEach { c -> Text("• ${c.wrong} → ${c.right}${if (c.why.isNotBlank()) " (${c.why})" else ""}") }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Role-plays
// ---------------------------------------------------------------------------------------------

private data class Line(val ai: Boolean, val text: String, val correction: String = "")

@Composable
fun ChatStep(step: Step.Chat, host: TaskHost) {
    val spec = step.spec
    val scope = rememberCoroutineScope()
    val lines = remember { mutableStateListOf(Line(true, spec.opening)) }
    val usedWords = remember { mutableStateListOf<String>() }
    val usedGrammar = remember { mutableStateListOf<String>() }
    var goal by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var failures by remember { mutableIntStateOf(0) }
    val startedAt = remember { SystemClock.elapsedRealtime() }
    var elapsed by remember { mutableLongStateOf(0L) }
    var debrief by remember { mutableStateOf<Evaluation?>(null) }
    var finishing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { host.speak(spec.opening) }
    LaunchedEffect(Unit) {
        while (true) { elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1000; delay(1000) }
    }

    val isDialog = step.kind == TaskKind.DIALOG
    val wordsOk = usedWords.size >= step.words
    val grammarOk = usedGrammar.size >= step.grammar
    val timeOk = elapsed >= step.minutes * 60L
    val complete = if (isDialog) wordsOk && grammarOk && timeOk else goal

    fun transcript() = lines.map { it.ai to it.text }

    fun send() {
        val tutor = host.tutor ?: return
        val text = input.trim()
        if (sending || text.isEmpty()) return
        sending = true; error = null
        lines += Line(false, text)
        input = ""
        scope.launch {
            val r = runCatching { host.busy { tutor.chatTurn(step.kind, spec, transcript()) } }
            sending = false
            r.onSuccess { t ->
                val i = lines.lastIndex
                if (t.correction.isNotBlank()) lines[i] = lines[i].copy(correction = t.correction)
                t.usedWords.forEach { w ->
                    val match = spec.targetWords.firstOrNull { it.equals(w.trim(), ignoreCase = true) } ?: w.trim().lowercase()
                    if (match.isNotEmpty() && match !in usedWords) usedWords += match
                }
                t.usedGrammar.filter { it in spec.targetGrammar && it !in usedGrammar }.forEach { usedGrammar += it }
                if (t.goalReached) goal = true
                lines += Line(true, t.reply)
                host.speak(t.reply)
            }.onFailure {
                // Put the message back so it can be sent again.
                lines.removeAt(lines.lastIndex)
                input = text
                error = errorText(it); failures++
            }
        }
    }

    fun finishChat() {
        val tutor = host.tutor
        if (finishing) return
        finishing = true
        scope.launch {
            val e = tutor?.let { t -> runCatching { host.busy { t.chatEvaluation(step.kind, spec, transcript()) } }.getOrNull() }
            if (e != null) {
                // The app decides pass/fail; the debrief only adds corrections and notes.
                val fixed = e.copy(criteria = e.criteria.map { it.copy(ok = true) })
                host.record(step.kind, spec.title, fixed)
                debrief = fixed
            } else host.complete(StepResult(true))
            finishing = false
        }
    }

    Hint(spec.situationRu)
    Text("Цель: ${spec.userGoalRu}", color = Warn)
    Panel {
        if (isDialog) {
            val left = (step.minutes * 60L - elapsed).coerceAtLeast(0)
            Text("${if (timeOk) "✓" else "⏳"} Время: ${if (timeOk) "${step.minutes} мин" else "%d:%02d".format(left / 60, left % 60)}")
            Text("${if (wordsOk) "✓" else "•"} Слова (${usedWords.size}/${step.words}): " +
                spec.targetWords.joinToString(" · ") { if (it in usedWords) "✓$it" else it })
            Text("${if (grammarOk) "✓" else "•"} Грамматика (${usedGrammar.size}/${step.grammar}): " +
                spec.targetGrammar.mapNotNull { id -> Curriculum.grammar(id)?.let { (if (id in usedGrammar) "✓" else "") + it.ru } }.joinToString(" · "))
        } else {
            Text("${if (goal) "✓" else "•"} Цель достигнута")
            if (spec.targetWords.isNotEmpty()) Text("Пригодятся слова: ${spec.targetWords.joinToString(" · ")}", color = Muted)
        }
    }

    lines.forEach { l ->
        Column(Modifier.fillMaxWidth(), horizontalAlignment = if (l.ai) Alignment.Start else Alignment.End) {
            Text(
                l.text,
                fontSize = 16.sp,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .background(if (l.ai) CardBg else Color(0xFF1E3A5F), RoundedCornerShape(12.dp))
                    .padding(10.dp),
            )
            if (l.correction.isNotBlank()) Text("✎ ${l.correction}", color = Warn, fontSize = 14.sp)
        }
    }
    if (sending) CircularProgressIndicator()
    error?.let { Text(it, color = Bad) }

    val d = debrief
    if (d != null) {
        EvaluationView(d)
        Button(onClick = { host.complete(StepResult(true)) }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Готово ✓") }
        return
    }
    if (complete) {
        Button(onClick = ::finishChat, enabled = !finishing, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(if (finishing) "Claude подводит итог…" else "Завершить диалог ✓", fontSize = 17.sp)
        }
    }
    AnswerField(input, { input = it; host.alive() }, enabled = !sending, label = "Твоя реплика (можно голосом)", minLines = 2)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = ::send, enabled = !sending && input.isNotBlank(), modifier = Modifier.weight(1f).height(52.dp)) { Text("Отправить") }
        OutlinedButton(onClick = { lines.lastOrNull { it.ai }?.let { host.speak(it.text) } }, modifier = Modifier.height(52.dp)) { Text("🔊") }
    }
    if (failures >= 2) OutlinedButton(onClick = { host.complete(StepResult(false)) }, modifier = Modifier.fillMaxWidth()) {
        Text("Claude не отвечает — задание без сети")
    }
}

// ---------------------------------------------------------------------------------------------
// Explain the word
// ---------------------------------------------------------------------------------------------

@Composable
fun AliasStep(step: Step.Alias, host: TaskHost) {
    val scope = rememberCoroutineScope()
    var index by remember { mutableIntStateOf(0) }
    var solved by remember { mutableIntStateOf(0) }
    val explanations = remember { mutableStateListOf<String>() }
    val wrong = remember { mutableStateListOf<String>() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var roundOver by remember { mutableStateOf<Boolean?>(null) } // true = guessed, false = failed
    val missed = remember { mutableStateListOf<Long>() }
    val guessed = remember { mutableStateListOf<Long>() }
    var failures by remember { mutableIntStateOf(0) }

    val word = step.words.getOrNull(index)
    if (word == null) {
        // Ran out of words: count what was achieved.
        LaunchedEffect(Unit) { host.complete(StepResult(solved >= step.required, missed.toSet(), guessed.toSet())) }
        return
    }

    fun nextWord() {
        if (solved >= step.required) {
            host.complete(StepResult(true, missed.toSet(), guessed.toSet()))
            return
        }
        index++; explanations.clear(); wrong.clear(); input = ""; message = null; roundOver = null
    }

    fun forbiddenUsed(text: String): Boolean {
        val tokens = TextUtil.normalize(text).split(Regex("""[^\p{L}']+""")).filter { it.isNotEmpty() }
        return word.variants.any { v ->
            val parts = v.split(' ').filter { it.length >= 3 }
            parts.any { p ->
                val root = if (p.length >= 5) p.take(p.length - 2) else p
                tokens.any { it == p || (root.length >= 4 && it.startsWith(root)) }
            }
        }
    }

    fun matches(guess: String): Boolean = TextUtil.isTypedAnswerCorrect(TextUtil.stripArticle(guess), word.variants)

    fun send() {
        val tutor = host.tutor ?: return
        val text = input.trim()
        if (busy || text.isEmpty() || roundOver != null) return
        if (forbiddenUsed(text)) { message = "Нельзя называть само слово или однокоренные — перефразируй"; return }
        busy = true; message = null
        explanations += text
        input = ""
        scope.launch {
            val r = runCatching { host.busy { tutor.aliasGuess(explanations.toList(), wrong.toList()) } }
            busy = false
            r.onSuccess { g ->
                if (matches(g.guess)) {
                    solved++; guessed += word.noteId; roundOver = true
                    message = "Claude угадал: «${g.guess}» ✓"
                } else {
                    wrong += g.guess
                    if (wrong.size >= 2) {
                        missed += word.noteId; roundOver = false
                        message = "Claude не угадал («${wrong.joinToString("», «")}»)"
                    } else message = "Claude думает, что это «${g.guess}». Добавь подсказку."
                }
            }.onFailure { explanations.removeAt(explanations.lastIndex); input = text; message = errorText(it); failures++ }
        }
    }

    Text("Угадано: $solved / ${step.required}", color = Muted)
    Hint("Объясни слово по-английски, не называя его и однокоренные. У Claude две попытки.")
    Text(word.main, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text(word.ru, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    explanations.forEach { Text("› $it") }
    if (roundOver == null) {
        AnswerField(input, { input = it; host.alive() }, enabled = !busy, label = "Объяснение (можно голосом)", minLines = 3)
        Button(onClick = ::send, enabled = !busy && input.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(if (busy) "Claude угадывает…" else "Отправить")
        }
    }
    message?.let { Text(it, color = if (roundOver == true) Good else Warn) }
    if (roundOver != null) Button(onClick = ::nextWord, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text(if (solved >= step.required) "Готово ✓" else "Следующее слово")
    }
    if (failures >= 2) OutlinedButton(onClick = { host.complete(StepResult(false)) }, modifier = Modifier.fillMaxWidth()) {
        Text("Claude не отвечает — задание без сети")
    }
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
                    val shown = if (revealed && bad) part.answer else inputs[i]
                    BasicTextField(
                        value = shown,
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
    }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Проверить (${checks}/3)") }
    if (wrong.isNotEmpty() && !passed && !revealed) Text("Ошибок: ${wrong.size}, можно ${d.allowedMistakes}. Красные поля — исправь.", color = Warn)
    if (passed) {
        Text("Засчитано ✓${if (wrong.isNotEmpty()) " (ошибок: ${wrong.size})" else ""}", color = Good)
        if (wrong.isNotEmpty()) Text(d.gaps.filter { it.index in wrong }.joinToString { it.answer }, color = Warn)
        Button(onClick = { host.complete(StepResult(true, missedWords = d.gaps.filter { it.index in wrong }.map { it.answer })) },
            modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Готово ✓") }
    }
    if (revealed) {
        Text("Три попытки кончились — ответы показаны красным.", color = Bad)
        Button(onClick = { host.complete(StepResult(false, missedWords = d.gaps.filter { it.index in wrong }.map { it.answer })) },
            modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Дальше") }
    }
}
