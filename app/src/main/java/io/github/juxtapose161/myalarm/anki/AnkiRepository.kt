package io.github.juxtapose161.myalarm.anki

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import io.github.juxtapose161.myalarm.Settings
import io.github.juxtapose161.myalarm.quiz.TextUtil
import io.github.juxtapose161.myalarm.quiz.Word

/**
 * Reads words from AnkiDroid through its public ContentProvider
 * (see FlashCardsContract in the AnkiDroid repo). No AnkiDroid library is needed.
 */
class AnkiRepository(context: Context) {

    private val ctx = context.applicationContext
    private val cr = ctx.contentResolver

    fun isInstalled(): Boolean =
        ctx.packageManager.resolveContentProvider(AUTHORITY, 0) != null

    fun hasPermission(): Boolean =
        ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun isReady() = isInstalled() && hasPermission()

    /** All deck names, sorted. */
    fun decks(): List<String> = safe(emptyList()) {
        cr.query(DECKS_URI, arrayOf("deck_name", "deck_id"), null, null, null)?.use { c ->
            val nameCol = c.getColumnIndexOrThrow("deck_name")
            buildList { while (c.moveToNext()) add(c.getString(nameCol)) }
        }.orEmpty().sortedBy { it.lowercase() }
    }

    /** Field names of note types used by notes matching the settings' query. */
    fun fieldNames(settings: Settings): List<String> = safe(emptyList()) {
        val mids = linkedSetOf<Long>()
        cr.query(NOTES_URI, arrayOf("_id", "mid"), settings.ankiQuery(), null, null)?.use { c ->
            val midCol = c.getColumnIndexOrThrow("mid")
            var n = 0
            while (c.moveToNext() && n++ < 300) mids += c.getLong(midCol)
        }
        mids.flatMap { modelFields(it) }.distinct()
    }

    /** Number of matching notes and a few parsed examples, for the settings screen. */
    fun preview(settings: Settings): Pair<Int, List<Word>> = safe(0 to emptyList()) {
        val notes = queryNotes(settings.ankiQuery(), 5000)
        notes.size to notes.take(3).mapNotNull { toWord(it, settings) }
    }

    /**
     * Words for one morning, in priority order:
     * previous mistakes, then cards due today, then random notes.
     */
    fun loadWords(settings: Settings, mistakes: Set<Long>, total: Int = 60): List<Word> = safe(emptyList()) {
        val base = settings.ankiQuery()
        val result = LinkedHashMap<Long, Word>()
        fun addAll(raw: List<RawNote>) {
            for (r in raw) {
                if (result.size >= total) return
                if (r.id in result) continue
                toWord(r, settings)?.let { result[r.id] = it }
            }
        }
        if (mistakes.isNotEmpty()) {
            addAll(queryNotes("$base nid:${mistakes.take(30).joinToString(",")}", 30).shuffled())
        }
        addAll(queryNotes("$base is:due", 500).shuffled().take(25))
        addAll(queryNotes(base, 3000).shuffled())
        result.values.toList()
    }

    /** Adds the tag "alarm_miss" to notes, so they can be found in Anki later. */
    fun tagMistakes(noteIds: Collection<Long>) {
        for (id in noteIds) safe(Unit) {
            val uri = Uri.withAppendedPath(NOTES_URI, id.toString())
            val tags = cr.query(uri, arrayOf("tags"), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) ?: "" else null
            } ?: return@safe
            if (tags.split(' ').any { it.equals(MISS_TAG, ignoreCase = true) }) return@safe
            val values = ContentValues().apply { put("tags", "${tags.trim()} $MISS_TAG".trim()) }
            cr.update(uri, values, null, null)
        }
    }

    // --- internals ---

    private class RawNote(val id: Long, val mid: Long, val fields: List<String>)

    private val modelCache = HashMap<Long, List<String>>()

    private fun modelFields(mid: Long): List<String> = modelCache.getOrPut(mid) {
        safe(emptyList()) {
            cr.query(Uri.withAppendedPath(MODELS_URI, mid.toString()), arrayOf("field_names"), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0).split(SEP) else emptyList() }
                .orEmpty()
        }
    }

    private fun queryNotes(search: String, limit: Int): List<RawNote> =
        cr.query(NOTES_URI, arrayOf("_id", "mid", "flds"), search, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow("_id")
            val midCol = c.getColumnIndexOrThrow("mid")
            val fldCol = c.getColumnIndexOrThrow("flds")
            buildList {
                while (c.moveToNext() && size < limit) {
                    add(RawNote(c.getLong(idCol), c.getLong(midCol), (c.getString(fldCol) ?: "").split(SEP)))
                }
            }
        }.orEmpty()

    private fun toWord(n: RawNote, s: Settings): Word? {
        val names = modelFields(n.mid)
        fun field(name: String, default: Int): String? {
            val i = if (name.isNotBlank()) names.indexOf(name).takeIf { it >= 0 } ?: default else default
            return n.fields.getOrNull(i)
        }
        val en = TextUtil.clean(field(s.enField, 0) ?: return null)
        val ru = TextUtil.clean(field(s.ruField, 1) ?: return null)
        if (en.isEmpty() || ru.isEmpty() || en.length > 80) return null
        val exIndex = when {
            s.exampleField.isNotBlank() -> names.indexOf(s.exampleField)
            else -> names.indexOfFirst { EXAMPLE_NAME.containsMatchIn(it) }
        }
        val example = n.fields.getOrNull(exIndex)?.let { TextUtil.clean(it) }?.takeIf { it.isNotEmpty() }
        return Word(n.id, en, ru, example)
    }

    private inline fun <T> safe(fallback: T, block: () -> T): T =
        try { block() } catch (e: Exception) {
            Log.w(TAG, "AnkiDroid query failed", e)
            fallback
        }

    companion object {
        private const val TAG = "AnkiRepository"
        const val PACKAGE = "com.ichi2.anki"
        const val AUTHORITY = "com.ichi2.anki.flashcards"
        const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
        const val MISS_TAG = "alarm_miss"
        private const val SEP = "\u001f"
        private val BASE: Uri = Uri.parse("content://$AUTHORITY")
        private val NOTES_URI: Uri = Uri.withAppendedPath(BASE, "notes")
        private val MODELS_URI: Uri = Uri.withAppendedPath(BASE, "models")
        private val DECKS_URI: Uri = Uri.withAppendedPath(BASE, "decks")
        private val EXAMPLE_NAME = Regex("(?i)example|sentence|context|пример|предложени")
    }
}
