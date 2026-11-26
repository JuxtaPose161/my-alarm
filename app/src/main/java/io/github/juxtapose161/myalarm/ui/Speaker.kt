package io.github.juxtapose161.myalarm.ui

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Thin wrapper over Android TextToSpeech, English voice, played on the alarm stream. */
class Speaker(context: Context, private val onReady: (Boolean) -> Unit) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context.applicationContext, this)
    var ready = false; private set
    private var pending: String? = null

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS && run {
            val r = tts.setLanguage(Locale.US)
            r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        }
        if (ready) {
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            tts.setSpeechRate(0.85f)
            pending?.let { speak(it) }
        }
        pending = null
        onReady(ready)
    }

    fun speak(text: String) {
        if (!ready) { pending = text; return }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "word")
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
