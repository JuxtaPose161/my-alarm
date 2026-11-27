package io.github.juxtapose161.myalarm.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.juxtapose161.myalarm.SettingsStore
import io.github.juxtapose161.myalarm.course.CourseStore
import io.github.juxtapose161.myalarm.course.Curriculum
import io.github.juxtapose161.myalarm.course.LearnerProfile
import io.github.juxtapose161.myalarm.course.Scenario
import io.github.juxtapose161.myalarm.course.TaskKind
import io.github.juxtapose161.myalarm.course.TaskParams
import io.github.juxtapose161.myalarm.course.ReviewFile
import io.github.juxtapose161.myalarm.course.SyncSettings
import io.github.juxtapose161.myalarm.data.Sync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun TabColumn(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun Note(text: String) = Text(text, color = Color.Gray)

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, step: Int = 1, format: (Int) -> String = { "$it" }, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { onChange((value - step).coerceIn(range)) }, enabled = value > range.first) { Text("−") }
        Text(format(value), modifier = Modifier.width(64.dp).padding(horizontal = 4.dp), fontWeight = FontWeight.SemiBold)
        OutlinedButton(onClick = { onChange((value + step).coerceIn(range)) }, enabled = value < range.last) { Text("+") }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
fun SyncTab(course: CourseStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sync by remember { mutableStateOf(course.sync) }
    var level by remember { mutableStateOf(course.level) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var stock by remember { mutableStateOf(course.stock()) }
    fun update(v: SyncSettings) { sync = v; course.sync = v.copy(lastSync = course.sync.lastSync, publishedHash = 0) }

    TabColumn {
        Text("Claude", style = MaterialTheme.typography.headlineSmall)
        Note(
            "Claude работает от твоей подписки в сессии Claude Code по расписанию. Ночью и днём она готовит задания " +
                "по курсу и разбирает твои ответы. Телефон и сессия обмениваются файлами через твой приватный " +
                "репозиторий на GitHub. Утром ответ проверяет телефон по критериям из задания, подробный разбор приходит позже."
        )
        OutlinedTextField(
            value = sync.repo,
            onValueChange = { update(sync.copy(repo = it.trim())) },
            label = { Text("Репозиторий данных (владелец/имя)") },
            placeholder = { Text("JuxtaPose161/my-alarm-data") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = sync.token,
            onValueChange = { update(sync.copy(token = it.trim())) },
            label = { Text("Токен GitHub (github_pat_…)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Note(
            "Токен: GitHub → Settings → Developer settings → Fine-grained tokens → доступ только к этому репозиторию, " +
                "права Contents: Read and write. Хранится только на телефоне."
        )
        Button(onClick = {
            busy = true; status = "Синхронизирую…"
            scope.launch {
                status = runCatching { Sync.run(context) }.fold(
                    { r -> "✓ Отправлено ответов: ${r.uploaded}, новых заданий: ${r.downloaded}, разборов: ${r.reviews}" },
                    { e -> "✗ ${e.message?.take(200)}" },
                )
                stock = course.stock()
                busy = false
            }
        }, enabled = sync.enabled && !busy, modifier = Modifier.fillMaxWidth()) { Text("Синхронизировать сейчас") }
        status?.let { Text(it) }
        val last = course.sync
        if (last.lastSync > 0) Note("Последняя синхронизация: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(last.lastSync))}")
        if (last.lastError.isNotBlank()) Text("Ошибка: ${last.lastError}", color = Warn)

        HorizontalDivider()
        Section("Уровень")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Curriculum.cefrLevels.forEach { l ->
                if (level == l) Button(onClick = {}) { Text(l) }
                else OutlinedButton(onClick = { course.level = l; level = l; Sync.soon(context) }) { Text(l) }
            }
        }
        val lvl = Curriculum.level(level)
        Note("Курс: ${lvl.book}. Темы: ${lvl.topics.joinToString(", ")}. Новый уровень Claude учтёт при следующей подготовке заданий.")

        HorizontalDivider()
        Section("Готовые задания на телефоне")
        val wanted = course.scenarios.flatMap { it.kindList }.filter { it.prepared }.distinct()
        if (wanted.isEmpty()) Note("В сценариях нет заданий от Claude.")
        wanted.forEach { k -> Text("${k.title}: ${stock[k] ?: 0}") }
        Note("Если задания нет, вместо него будет перевод слов (или карточки), так что будильник выключится и без сети.")
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
fun ScenariosTab(course: CourseStore, onTest: (String) -> Unit) {
    var scenarios by remember { mutableStateOf(course.scenarios) }
    fun save(list: List<Scenario>) { scenarios = list; course.scenarios = list }
    fun edit(i: Int, change: (Scenario) -> Scenario) = save(scenarios.toMutableList().also { it[i] = change(it[i]) })

    TabColumn {
        Text("Сценарии", style = MaterialTheme.typography.headlineSmall)
        Note(
            "Sleep не сообщает капче, какой будильник её открыл, поэтому задания выбираются по времени: " +
                "срабатывает первый сценарий, в окно которого попало время. Утренний будильник ставь в Sleep на время " +
                "утреннего сценария, а «задания по расписанию» — отдельными будильниками Sleep с противным звуком " +
                "на время дневного. Из выбранных заданий берётся то, что давно не попадалось."
        )
        scenarios.forEachIndexed { i, s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = s.name, onValueChange = { v -> edit(i) { it.copy(name = v) } },
                        label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Stepper("С", s.from, 0..(24 * 60 - 30), 30, Scenario::format) { v -> edit(i) { it.copy(from = v) } }
                    Stepper("До", s.to, 0..(24 * 60 - 30), 30, Scenario::format) { v -> edit(i) { it.copy(to = v) } }
                    Stepper("Разминка, карточек", s.warmupCards, 0..10) { v -> edit(i) { it.copy(warmupCards = v) } }
                    Stepper("Заданий подряд", s.tasks, 1..3) { v -> edit(i) { it.copy(tasks = v) } }
                    Stepper("Запасной выход через", s.escapeMinutes, 0..60, 5, { if (it == 0) "нет" else "$it мин" }) { v ->
                        edit(i) { it.copy(escapeMinutes = v) }
                    }
                    Note("Запасной выход: через это время появляется кнопка «решить примеры вместо заданий», чтобы не опоздать.")
                    Text("Задания:", fontWeight = FontWeight.SemiBold)
                    TaskKind.entries.forEach { k ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = k.name in s.kinds, onCheckedChange = { on ->
                                edit(i) { sc -> sc.copy(kinds = if (on) sc.kinds + k.name else sc.kinds - k.name) }
                            })
                            Text(k.title + if (k.prepared) "" else " (без Claude)")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onTest(s.id) }) { Text("Попробовать") }
                        if (i > 0) OutlinedButton(onClick = {
                            save(scenarios.toMutableList().also { l -> l.add(i - 1, l.removeAt(i)) })
                        }) { Text("↑") }
                        if (scenarios.size > 1) OutlinedButton(onClick = { save(scenarios.filterIndexed { j, _ -> j != i }) }) {
                            Text("Удалить")
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = {
            save(scenarios + Scenario("s${System.currentTimeMillis()}", "Новый сценарий", 18 * 60, 22 * 60, 0,
                listOf(TaskKind.REPHRASE.name, TaskKind.ERRORS.name), escapeMinutes = 20))
        }, modifier = Modifier.fillMaxWidth()) { Text("Добавить сценарий") }
        OutlinedButton(onClick = { save(Scenario.defaults) }, modifier = Modifier.fillMaxWidth()) { Text("Вернуть сценарии по умолчанию") }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
fun TasksTab(course: CourseStore, onTest: (TaskKind) -> Unit) {
    var p by remember { mutableStateOf(course.params) }
    fun upd(v: TaskParams) { p = v; course.params = v }

    @Composable
    fun Kind(k: TaskKind, content: @Composable () -> Unit = {}) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(k.title, style = MaterialTheme.typography.titleMedium)
                Note(k.description)
                content()
                OutlinedButton(onClick = { onTest(k) }) { Text("Попробовать") }
            }
        }
    }

    TabColumn {
        Text("Задания", style = MaterialTheme.typography.headlineSmall)
        Note("Отвечать можно текстом или голосом — микрофоном на клавиатуре. Ответы пробного запуска тоже уходят Claude на разбор.")
        Kind(TaskKind.TRANSLATE) { Stepper("Слов (N)", p.translateWords, 1..20) { upd(p.copy(translateWords = it)) } }
        Kind(TaskKind.DIALOG) {
            Stepper("Не короче, мин", p.dialogMinutes, 0..15) { upd(p.copy(dialogMinutes = it)) }
            Stepper("Реплик собеседника", p.dialogTurns, 3..10) { upd(p.copy(dialogTurns = it)) }
            Stepper("Слов из Anki (N)", p.dialogWords, 0..8) { upd(p.copy(dialogWords = it)) }
            Stepper("Конструкций (M)", p.dialogGrammar, 0..4) { upd(p.copy(dialogGrammar = it)) }
        }
        Kind(TaskKind.RETELL) {
            Stepper("Вопросов (N)", p.retellQuestions, 1..6) { upd(p.copy(retellQuestions = it)) }
            Stepper("Слов из Anki (M)", p.retellWords, 0..8) { upd(p.copy(retellWords = it)) }
        }
        Kind(TaskKind.REPHRASE) { Stepper("Вариантов", p.rephraseCount, 1..5) { upd(p.copy(rephraseCount = it)) } }
        Kind(TaskKind.ERRORS) { Stepper("Ошибок в тексте", p.errorsCount, 2..10) { upd(p.copy(errorsCount = it)) } }
        Kind(TaskKind.BACK_TRANSLATE) {
            Stepper("Слов из Anki", p.backTranslateWords, 0..8) { upd(p.copy(backTranslateWords = it)) }
            Stepper("Конструкций", p.backTranslateGrammar, 0..4) { upd(p.copy(backTranslateGrammar = it)) }
        }
        Kind(TaskKind.CUE_CARD) { Stepper("Минимум слов", p.cueCardMinWords, 40..300, 20) { upd(p.copy(cueCardMinWords = it)) } }
        Kind(TaskKind.DAY_PLAN) { Stepper("Конструкций", p.storyGrammar, 1..5) { upd(p.copy(storyGrammar = it)) } }
        Kind(TaskKind.YESTERDAY) { Note("Число конструкций — то же, что в «Плане дня»: ${p.storyGrammar}") }
        Kind(TaskKind.DICTATION) { Stepper("Пропусков", p.dictationGaps, 3..15) { upd(p.copy(dictationGaps = it)) } }
        Kind(TaskKind.QUICK) { Note("Число карточек задаёт сложность в Sleep (1–5).") }
        Note("Размеры заданий уходят Claude при синхронизации и касаются новых заданий.")
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
fun ProgressTab(course: CourseStore, store: SettingsStore) {
    val profile = remember { course.profile }
    val reviews = remember { course.reviews() }
    val level = course.level

    TabColumn {
        Text("Прогресс", style = MaterialTheme.typography.headlineSmall)
        Text("Уровень $level · ${Curriculum.level(level).book}")
        if (profile.summary.isNotBlank()) Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Заметки Claude", fontWeight = FontWeight.SemiBold)
                Text(profile.summary)
                if (profile.nextFocus.isNotEmpty()) Text("Дальше: ${profile.nextFocus.joinToString("; ")}", color = Color.Gray)
                if (profile.updatedAt.isNotBlank()) Note("Обновлено ${profile.updatedAt}")
            }
        } else Note("Заметки появятся после первого разбора от Claude.")

        if (reviews.isNotEmpty()) {
            Section("Разборы Claude")
            reviews.take(10).forEach { r -> ReviewCard(r) }
        }

        Section("Грамматика уровня")
        Curriculum.level(level).grammar.forEach { g -> GrammarRow(profile, g) }
        val below = Curriculum.grammarUpTo(level).filter { it !in Curriculum.level(level).grammar }
            .filter { (profile.grammar[it.id]?.errors ?: 0) > 0 }
        if (below.isNotEmpty()) {
            Section("Повторить с прошлых уровней")
            below.forEach { g -> GrammarRow(profile, g) }
        }
        if (profile.weaknesses.isNotEmpty()) {
            Section("Над чем работать")
            profile.weaknesses.takeLast(6).reversed().forEach { Text("• $it") }
        }
        if (profile.strengths.isNotEmpty()) {
            Section("Получается")
            profile.strengths.takeLast(4).reversed().forEach { Text("• $it") }
        }
        val hard = profile.struggling(10)
        if (hard.isNotEmpty()) {
            Section("Трудные слова")
            Text(hard.joinToString(" · "))
        }
        Section("Последние задания на телефоне")
        val history = course.history.takeLast(20).reversed()
        if (history.isEmpty()) Note("Пока пусто")
        history.forEach { h ->
            val kind = TaskKind.byName(h.kind)?.title ?: if (h.kind == "ESCAPE") "Запасной выход" else h.kind
            Text("${h.day} · $kind${if (h.title.isNotBlank() && h.title != kind) " «${h.title}»" else ""} ${if (h.passed) "✓" else "✗"}")
        }
        val old = remember { store.history().takeLast(7).reversed() }
        if (old.isNotEmpty()) {
            Section("Слова по дням")
            old.forEach { line ->
                val p = line.split('|')
                if (p.size == 3) Text("${p[0]}: верно ${p[1]}, ошибок ${p[2]}")
            }
        }
    }
}

@Composable
private fun ReviewCard(r: ReviewFile) {
    var open by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val kind = TaskKind.byName(r.kind)?.title ?: r.kind
            Text("${if (r.passed) "✓" else "✗"} $kind${if (r.title.isNotBlank()) " «${r.title}»" else ""}", fontWeight = FontWeight.SemiBold)
            if (r.feedbackRu.isNotBlank()) Text(r.feedbackRu)
            if (open) {
                r.criteria.forEach { c -> Text("${if (c.ok) "✓" else "✗"} ${c.name}${if (c.comment.isNotBlank()) " — ${c.comment}" else ""}") }
                r.corrections.forEach { c -> Text("• ${c.wrong} → ${c.right}${if (c.why.isNotBlank()) " (${c.why})" else ""}", color = Warn) }
                if (r.modelAnswer.isNotBlank()) Text("Как можно было: ${r.modelAnswer}", color = Color.Gray)
            }
            if (r.corrections.isNotEmpty() || r.criteria.isNotEmpty() || r.modelAnswer.isNotBlank()) {
                OutlinedButton(onClick = { open = !open }) { Text(if (open) "Свернуть" else "Подробнее (${r.corrections.size} исправл.)") }
            }
        }
    }
}

@Composable
private fun GrammarRow(profile: LearnerProfile, g: Curriculum.Grammar) {
    val s = profile.grammar[g.id]
    val mark = when {
        profile.isMastered(g.id) -> "✓"
        s == null -> "·"
        s.errors > s.ok -> "!"
        else -> "…"
    }
    Text("$mark ${g.ru} — ${g.name}${s?.let { "  (верно ${it.ok}, ошибок ${it.errors})" } ?: ""}",
        color = if (mark == "✓") Good else if (mark == "!") Warn else Color.Unspecified)
}
