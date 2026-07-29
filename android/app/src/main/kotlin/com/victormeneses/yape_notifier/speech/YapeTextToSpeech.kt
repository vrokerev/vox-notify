package com.victormeneses.yape_notifier.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.victormeneses.yape_notifier.BuildConfig
import java.util.Locale

class YapeTextToSpeech(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    private val pending = ArrayDeque<String>()
    private var ready = false
    private var failed = false

    init {
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "tts done=$utteranceId")
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "tts error=$utteranceId")
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "tts error=$utteranceId code=$errorCode")
                }
            },
        )
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ready = setBestSpanishLocale()
            if (ready) {
                flushPending()
            } else {
                failed = true
                if (BuildConfig.DEBUG) Log.d(TAG, "tts language unsupported")
            }
        } else {
            failed = true
            if (BuildConfig.DEBUG) Log.d(TAG, "tts init failed status=$status")
        }
    }

    fun speak(text: String) {
        if (text.isBlank() || failed) return
        if (!ready) {
            if (pending.size >= MAX_PENDING) pending.removeFirst()
            pending.addLast(text)
            if (BuildConfig.DEBUG) Log.d(TAG, "tts queued pending=${pending.size}")
            return
        }
        speakNow(text, TextToSpeech.QUEUE_ADD)
    }

    fun stop() {
        pending.clear()
        tts.stop()
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    private fun setBestSpanishLocale(): Boolean =
        listOf(
            Locale.forLanguageTag("es-PE"),
            Locale.forLanguageTag("es-419"),
            Locale("es", "ES"),
            Locale("es"),
        ).any { locale ->
            val result = tts.setLanguage(locale)
            result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        }

    private fun flushPending() {
        while (pending.isNotEmpty()) {
            speakNow(pending.removeFirst(), TextToSpeech.QUEUE_ADD)
        }
    }

    private fun speakNow(text: String, queueMode: Int) {
        tts.speak(text, queueMode, null, "yape-notifier-${System.nanoTime()}")
    }

    companion object {
        private const val TAG = "YapeNotifier"
        private const val MAX_PENDING = 5
    }
}
