package io.github.juxtapose161.myalarm.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.juxtapose161.myalarm.Settings
import io.github.juxtapose161.myalarm.SettingsStore
import io.github.juxtapose161.myalarm.anki.AnkiRepository
import io.github.juxtapose161.myalarm.quiz.TaskType
import io.github.juxtapose161.myalarm.course.CourseStore
import io.github.juxtapose161.myalarm.course.Preparer
import io.github.juxtapose161.myalarm.course.TaskKind
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SettingsStore(this)
        val repo = AnkiRepository(this)
        val course = CourseStore(this)
        Preparer.schedule(this)
        val test: (Intent.() -> Unit) -> Unit = { extras ->
            startActivity(Intent(this, CaptchaActivity::class.java).putExtra(CaptchaActivity.EXTRA_TEST_DIFFICULTY, 3).apply(extras))
        }
        setContent {
            AppTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                Column(Modifier.fillMaxSize()) {
                    ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                        listOf("Anki", "Claude", "Сценарии", "Задания", "Прогресс").forEachIndexed { i, t ->
                            Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                        }
                    }
                    when (tab) {
                        1 -> ClaudeTab(course)
                        2 -> ScenariosTab(course) { id -> test { putExtra(CaptchaActivity.EXTRA_TEST_SCENARIO, id) } }
                        3 -> TasksTab(course) { kind -> test { putExtra(CaptchaActivity.EXTRA_TEST_KIND, kind.name) } }
                        4 -> ProgressTab(course, store)
                        else -> AnkiTab(
                            store = store,
                            repo = repo,
                            onTest = { difficulty ->
                                startActivity(
                                    Intent(this@SettingsActivity, CaptchaActivity::class.java)
                                        .putExtra(CaptchaActivity.EXTRA_TEST_DIFFICULTY, difficulty)
                                        .putExtra(CaptchaActivity.EXTRA_TEST_KIND, TaskKind.QUICK.name)
                                )
                            },
                            openStore = ::openAnkiInStore,
                        )
                    }
                }
            }
        }
    }

    private fun openAnkiInStore() {
        val uri = Uri.parse("market://details?id=${AnkiRepository.PACKAGE}")
        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${AnkiRepository.PACKAGE}")))
        }
    }
}

@Composable
private fun AnkiTab(
    store: SettingsStore,
    repo: AnkiRepository,
    onTest: (Int) -> Unit,
    openStore: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(store.load()) }
    fun update(s: Settings) { settings = s; store.save(s) }

    var installed by remember { mutableStateOf(repo.isInstalled()) }
    var permitted by remember { mutableStateOf(repo.hasPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permitted = it
    }

    var decks by remember { mutableStateOf<List<String>>(emptyList()) }
    var fields by remember { mutableStateOf<List<String>>(emptyList()) }
    var preview by remember { mutableStateOf<String?>(null) }
    var testDifficulty by remember { mutableIntStateOf(3) }

    LaunchedEffect(permitted) {
        installed = repo.isInstalled()
        if (installed && permitted) decks = withContext(Dispatchers.IO) { repo.decks() }
    }
    LaunchedEffect(permitted, settings.deck, settings.level, settings.extraFilter) {
        if (installed && permitted) fields = withContext(Dispatchers.IO) { repo.fieldNames(settings) }
    }

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Английский будильник", style = MaterialTheme.typography.headlineSmall)
        Text(
            "В Sleep as Android: Настройки → Будильник → CAPTCHA → выбери «Английский из Anki» и сложность. " +
                "Какие задания будут, решает сценарий по времени (вкладка «Сценарии»). " +
                "Сложность 1–5 задаёт число быстрых карточек (3, 4, 5, 7, 10) и трудность примеров.",
            color = Color.Gray,
        )

        // --- Anki connection ---
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("AnkiDroid", style = MaterialTheme.typography.titleMedium)
                when {
                    !installed -> {
                        Text("AnkiDroid не установлен. Без него будут только примеры.")
                        Button(onClick = openStore) { Text("Установить AnkiDroid") }
                    }
                    !permitted -> {
                        Text("Нужен доступ к карточкам Anki.")
                        Button(onClick = { permissionLauncher.launch(AnkiRepository.PERMISSION) }) { Text("Дать доступ") }
                    }
                    else -> Text("✓ Подключено, колод: ${decks.size}", color = Color(0xFF81C784))
                }
            }
        }

        if (installed && permitted) {
            Section("Колода")
            Picker(
                label = settings.deck.ifBlank { "Все колоды" },
                options = listOf("") + decks,
                display = { it.ifBlank { "Все колоды" } },
                onPick = { update(settings.copy(deck = it, enField = "", ruField = "", exampleField = "")) },
            )

            Section("Уровень (фильтр по тегу)")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Settings.LEVELS.forEach { level ->
                    val label = level.ifBlank { "Любой" }
                    if (settings.level == level) Button(onClick = {}) { Text(label) }
                    else OutlinedButton(onClick = { update(settings.copy(level = level)) }) { Text(label) }
                }
            }
            Text(
                "Берутся карточки с тегом уровня, например B1 или CEFR::B1. Если тегов уровня в колоде нет, оставь «Любой» " +
                    "и выбирай уровень колодой.",
                color = Color.Gray,
            )
            OutlinedTextField(
                value = settings.extraFilter,
                onValueChange = { update(settings.copy(extraFilter = it)) },
                label = { Text("Доп. фильтр Anki (необязательно)") },
                placeholder = { Text("например: tag:verbs -is:new") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Section("Поля карточки")
            val auto = "— авто —"
            FieldPicker("Английское слово", settings.enField, fields, "$auto (1-е поле)") { update(settings.copy(enField = it)) }
            FieldPicker("Перевод", settings.ruField, fields, "$auto (2-е поле)") { update(settings.copy(ruField = it)) }
            FieldPicker("Пример предложения", settings.exampleField, fields, "$auto (поле Example/Пример)") {
                update(settings.copy(exampleField = it))
            }
            OutlinedButton(onClick = {
                scope.launch {
                    preview = "Проверяю…"
                    val (count, sample) = withContext(Dispatchers.IO) { repo.preview(settings) }
                    preview = if (count == 0) "Ничего не найдено. Проверь колоду и фильтр." else buildString {
                        append("Найдено карточек: $count")
                        sample.forEach { w ->
                            append("\n• ${w.en} — ${w.ru}")
                            w.example?.let { append("\n   «$it»") }
                        }
                    }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Проверить колоду") }
            preview?.let { Text(it) }
        }

        // --- Task types ---
        Section("Быстрые карточки")
        TaskType.entries.forEach { type ->
            val checked = type in settings.types
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = { on ->
                    val next = if (on) settings.types + type else settings.types - type
                    if (next.isNotEmpty()) update(settings.copy(types = next))
                })
                Text(type.title)
            }
        }
        Text(
            "Задачи со словами требуют Anki. Ошибка добавляет ещё одно задание (максимум +5), " +
                "а слово вернётся через пару вопросов и завтра утром.",
            color = Color.Gray,
        )

        if (installed && permitted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Помечать ошибки в Anki")
                    Text("Тег «${AnkiRepository.MISS_TAG}» — найдёшь их поиском в Anki", color = Color.Gray)
                }
                Switch(checked = settings.tagMistakesInAnki, onCheckedChange = { update(settings.copy(tagMistakesInAnki = it)) })
            }
        }

        HorizontalDivider()
        Section("Пробный запуск быстрых карточек")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..5).forEach { d ->
                if (d == testDifficulty) Button(onClick = {}) { Text("$d") }
                else OutlinedButton(onClick = { testDifficulty = d }) { Text("$d") }
            }
        }
        Button(onClick = { onTest(testDifficulty) }, modifier = Modifier.fillMaxWidth()) {
            Text("Попробовать (сложность $testDifficulty)")
        }
        Text("Пробный запуск не сохраняет ошибки и не трогает Anki.", color = Color.Gray)

    }
}

@Composable
internal fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
}

@Composable
internal fun <T> Picker(label: String, options: List<T>, display: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(display(option)) }, onClick = { open = false; onPick(option) })
            }
        }
    }
}

@Composable
private fun FieldPicker(title: String, value: String, fields: List<String>, autoLabel: String, onPick: (String) -> Unit) {
    Column {
        Text(title, color = Color.Gray)
        Picker(
            label = value.ifBlank { autoLabel },
            options = listOf("") + fields,
            display = { it.ifBlank { autoLabel } },
            onPick = onPick,
        )
    }
}
