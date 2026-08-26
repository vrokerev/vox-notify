package com.victormeneses.yape_notifier.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.victormeneses.yape_notifier.BuildConfig
import com.victormeneses.yape_notifier.storage.ListenerDiagnosticsRepository
import java.util.Locale

class YapeTextToSpeech(
    context: Context,
    private val diagnosticsRepository: ListenerDiagnosticsRepository? = null,
) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val pending = ArrayDeque<String>()
    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var speaking = false
    private var initRetryCount = 0
    private var initRetryScheduled = false
    private val retryHandler = Handler(Looper.getMainLooper())

    // VoxNotify announces an incoming notification. Treat the spoken phrase as notification
    // audio instead of media playback. This deliberately avoids requestAudioFocus(), which
    // Android 15/HyperOS can deny to a background process even while our monitoring FGS is alive.
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val utteranceListener =
        object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                speaking = true
                diagnosticsRepository?.update(
                    mapOf(
                        "lastTtsStartedAt" to System.currentTimeMillis().toString(),
                        "ttsState" to "started",
                        "lastAudioFocusResult" to "not_requested_notification_audio",
                    ),
                )
                if (BuildConfig.DEBUG) Log.d(TAG, "tts start=$utteranceId usage=notification")
            }

            override fun onDone(utteranceId: String?) {
                speaking = false
                diagnosticsRepository?.update(
                    mapOf(
                        "lastTtsCompletedAt" to System.currentTimeMillis().toString(),
                        "ttsState" to "done",
                    ),
                )
                if (BuildConfig.DEBUG) Log.d(TAG, "tts done=$utteranceId")
                speakNextIfNeeded()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                handleUtteranceError(utteranceId, "unknown")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                handleUtteranceError(utteranceId, errorCode.toString())
            }
        }

    init {
        initializeTts("initial")
    }

    override fun onInit(status: Int) {
        initRetryScheduled = false
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts
            if (engine == null) {
                scheduleTtsReinitialization("engine_missing_after_init")
                return
            }
            engine.setAudioAttributes(audioAttributes)
            engine.setOnUtteranceProgressListener(utteranceListener)
            ready = setBestSpanishLocale(engine)
            failed = !ready
            if (ready) {
                initRetryCount = 0
                diagnosticsRepository?.update(
                    mapOf(
                        "ttsState" to "ready",
                        "lastTtsError" to "",
                        "ttsAudioUsage" to "notification",
                        "ttsFocusGain" to "not_requested",
                        "lastAudioFocusResult" to "not_requested_notification_audio",
                        "ttsEngine" to runCatching { engine.defaultEngine.orEmpty() }.getOrDefault(""),
                    ),
                )
                if (BuildConfig.DEBUG) Log.d(TAG, "tts ready usage=notification focus=not_requested")
                flushPending()
            } else {
                diagnosticsRepository?.update(
                    mapOf(
                        "ttsState" to "language_unsupported",
                        "lastTtsError" to "language_unsupported",
                    ),
                )
                if (BuildConfig.DEBUG) Log.d(TAG, "tts language unsupported")
                scheduleTtsReinitialization("language_unsupported")
            }
        } else {
            ready = false
            failed = true
            diagnosticsRepository?.update(
                mapOf(
                    "ttsState" to "init_failed",
                    "lastTtsError" to "init_status:$status",
                ),
            )
            if (BuildConfig.DEBUG) Log.d(TAG, "tts init failed status=$status")
            scheduleTtsReinitialization("init_status:$status")
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        enqueue(text)

        diagnosticsRepository?.update(
            mapOf(
                "lastTtsQueuedAt" to System.currentTimeMillis().toString(),
                "ttsAudioUsage" to "notification",
                "ttsFocusGain" to "not_requested",
                "lastAudioFocusResult" to "not_requested_notification_audio",
            ),
        )

        if (failed) {
            diagnosticsRepository?.update(mapOf("ttsState" to "queued_engine_recovery"))
            // A TTS engine can be unavailable during early boot. A later Yape must recover it
            // instead of remaining permanently muted until the listener is recreated.
            scheduleTtsReinitialization("speak_after_failure", immediate = true)
            return
        }

        if (!ready) {
            diagnosticsRepository?.update(mapOf("ttsState" to "queued_before_init"))
            if (BuildConfig.DEBUG) Log.d(TAG, "tts queued pending=${pending.size}")
            return
        }
        speakNextIfNeeded()
    }

    fun stop() {
        pending.clear()
        retryHandler.removeCallbacksAndMessages(null)
        initRetryScheduled = false
        speaking = false
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    private fun initializeTts(reason: String) {
        ready = false
        failed = false
        initRetryScheduled = false
        runCatching { tts?.shutdown() }
        tts = null

        diagnosticsRepository?.update(
            mapOf(
                "ttsState" to "initializing",
                "ttsInitReason" to reason,
                "ttsInitAttempt" to (initRetryCount + 1).toString(),
                "ttsAudioUsage" to "notification",
                "ttsFocusGain" to "not_requested",
            ),
        )

        val engine = TextToSpeech(appContext, this)
        tts = engine
        // Configure immediately and again in onInit: OEM TTS engines differ in when they honor
        // these calls during their asynchronous bind.
        engine.setAudioAttributes(audioAttributes)
        engine.setOnUtteranceProgressListener(utteranceListener)
    }

    private fun scheduleTtsReinitialization(reason: String, immediate: Boolean = false) {
        if (initRetryScheduled) return
        if (speaking) return

        initRetryCount += 1
        val delay = if (immediate) {
            250L
        } else {
            (TTS_INIT_RETRY_BASE_MS * initRetryCount).coerceAtMost(TTS_INIT_RETRY_MAX_DELAY_MS)
        }
        initRetryScheduled = true
        diagnosticsRepository?.update(
            mapOf(
                "ttsRecoveryState" to "scheduled",
                "ttsRecoveryReason" to reason,
                "ttsRecoveryDelayMs" to delay.toString(),
            ),
        )
        retryHandler.postDelayed(
            {
                initRetryScheduled = false
                initializeTts("recovery:$reason")
            },
            delay,
        )
    }

    private fun enqueue(text: String) {
        if (pending.size >= MAX_PENDING) pending.removeFirst()
        pending.addLast(text)
    }

    private fun setBestSpanishLocale(engine: TextToSpeech): Boolean =
        listOf(
            Locale.forLanguageTag("es-PE"),
            Locale.forLanguageTag("es-419"),
            Locale("es", "ES"),
            Locale("es"),
        ).any { locale ->
            val result = engine.setLanguage(locale)
            result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        }

    private fun flushPending() {
        speakNextIfNeeded()
    }

    private fun speakNextIfNeeded() {
        if (!ready || speaking || pending.isEmpty()) return
        val text = pending.removeFirst()
        speakNow(text)
    }

    private fun speakNow(text: String) {
        val engine = tts
        if (engine == null) {
            pending.addFirst(text)
            failed = true
            scheduleTtsReinitialization("engine_null_before_speak", immediate = true)
            return
        }

        val streamVolume = runCatching {
            audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
        }.getOrDefault(-1)
        val streamMax = runCatching {
            audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)
        }.getOrDefault(-1)
        val utteranceId = "voxnotify-${System.nanoTime()}"

        diagnosticsRepository?.update(
            mapOf(
                "ttsState" to "speak_called",
                "lastTtsSpeakCalledAt" to System.currentTimeMillis().toString(),
                "ttsAudioUsage" to "notification",
                "ttsFocusGain" to "not_requested",
                "lastAudioFocusResult" to "not_requested_notification_audio",
                "ttsNotificationVolume" to "$streamVolume/$streamMax",
                "ttsUtteranceId" to utteranceId,
            ),
        )
        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "tts speak call=$utteranceId usage=notification focus=not_requested volume=$streamVolume/$streamMax",
            )
        }

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (result == TextToSpeech.ERROR) {
            pending.addFirst(text)
            failed = true
            speaking = false
            diagnosticsRepository?.update(
                mapOf(
                    "lastTtsError" to "speak_returned_error",
                    "ttsState" to "speak_failed",
                ),
            )
            if (BuildConfig.DEBUG) Log.d(TAG, "tts speak returned ERROR")
            scheduleTtsReinitialization("speak_returned_error", immediate = true)
            return
        }

        // Mark the utterance as in-flight immediately. onStart() will reaffirm it when the engine
        // actually begins playback. This prevents a second notification arriving in the tiny gap
        // before Google's TTS callback from flushing the first one.
        speaking = true
    }

    private fun handleUtteranceError(utteranceId: String?, code: String) {
        speaking = false
        diagnosticsRepository?.update(
            mapOf(
                "lastTtsError" to "$code:$utteranceId",
                "ttsState" to "error",
            ),
        )
        if (BuildConfig.DEBUG) Log.d(TAG, "tts error=$utteranceId code=$code")
        speakNextIfNeeded()
    }

    companion object {
        private const val TAG = "VoxNotify"
        private const val MAX_PENDING = 5
        private const val TTS_INIT_RETRY_BASE_MS = 1_500L
        private const val TTS_INIT_RETRY_MAX_DELAY_MS = 10_000L
    }
}
