package com.victormeneses.yape_notifier.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class YapeTextToSpeech(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val locale = Locale.forLanguageTag("es-PE")
            val result = tts.setLanguage(locale)
            ready = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    fun speak(text: String) {
        if (ready) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "yape-notifier-${System.nanoTime()}")
        }
    }

    fun shutdown() {
        tts.shutdown()
    }
}
