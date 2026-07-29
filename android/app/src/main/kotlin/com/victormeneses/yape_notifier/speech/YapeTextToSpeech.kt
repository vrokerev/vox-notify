package com.victormeneses.yape_notifier.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.victormeneses.yape_notifier.BuildConfig
import com.victormeneses.yape_notifier.notifications.VoxNotifyForegroundService
import com.victormeneses.yape_notifier.storage.ListenerDiagnosticsRepository
import java.util.Locale

class YapeTextToSpeech(
    context: Context,
    private val diagnosticsRepository: ListenerDiagnosticsRepository? = null,
) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val tts = TextToSpeech(appContext, this)
    private val pending = ArrayDeque<String>()
    private var ready = false
    private var failed = false
    private var speaking = false
    private val retryHandler = Handler(Looper.getMainLooper())
    private val focusRetries = mutableMapOf<String, Int>()
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener { }
                .build()
        } else {
            null
        }

    init {
        tts.setAudioAttributes(audioAttributes)
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    speaking = true
                    diagnosticsRepository?.update(
                        mapOf(
                            "lastTtsStartedAt" to System.currentTimeMillis().toString(),
                            "ttsState" to "started",
                        ),
                    )
                    if (BuildConfig.DEBUG) Log.d(TAG, "tts start=$utteranceId")
                }
                override fun onDone(utteranceId: String?) {
                    speaking = false
                    abandonFocus()
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
                    speaking = false
                    abandonFocus()
                    diagnosticsRepository?.update(
                        mapOf(
                            "lastTtsError" to "unknown:$utteranceId",
                            "ttsState" to "error",
                        ),
                    )
                    if (BuildConfig.DEBUG) Log.d(TAG, "tts error=$utteranceId")
                    speakNextIfNeeded()
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    speaking = false
                    abandonFocus()
                    diagnosticsRepository?.update(
                        mapOf(
                            "lastTtsError" to "$errorCode:$utteranceId",
                            "ttsState" to "error",
                        ),
                    )
                    if (BuildConfig.DEBUG) Log.d(TAG, "tts error=$utteranceId code=$errorCode")
                    speakNextIfNeeded()
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
            diagnosticsRepository?.update(
                mapOf(
                    "lastTtsQueuedAt" to System.currentTimeMillis().toString(),
                    "ttsState" to "queued_before_init",
                ),
            )
            if (BuildConfig.DEBUG) Log.d(TAG, "tts queued pending=${pending.size}")
            return
        }
        pending.addLast(text)
        speakNextIfNeeded()
    }

    fun stop() {
        pending.clear()
        retryHandler.removeCallbacksAndMessages(null)
        focusRetries.clear()
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
        speakNextIfNeeded()
    }

    private fun speakNextIfNeeded() {
        if (!ready || speaking || pending.isEmpty()) return
        val text = pending.first()
        if (appContext.applicationInfo.targetSdkVersion >= 35 && !VoxNotifyForegroundService.isRunning()) {
            val retry = (focusRetries["foreground:$text"] ?: 0) + 1
            VoxNotifyForegroundService.start(appContext)
            diagnosticsRepository?.update(
                mapOf(
                    "ttsState" to "waiting_for_foreground_before_focus",
                    "lastTtsFocusRetryAt" to System.currentTimeMillis().toString(),
                ),
            )
            if (retry <= MAX_FOCUS_RETRIES) {
                focusRetries["foreground:$text"] = retry
                retryHandler.postDelayed({ speakNextIfNeeded() }, FOCUS_RETRY_DELAY_MS)
            } else {
                diagnosticsRepository?.update(
                    mapOf(
                        "lastTtsError" to "foreground_service_not_ready",
                        "ttsState" to "audio_focus_failed",
                    ),
                )
            }
            return
        }
        if (!requestFocus()) {
            val retry = (focusRetries[text] ?: 0) + 1
            diagnosticsRepository?.update(
                mapOf(
                    "lastTtsError" to "audio_focus_denied",
                    "lastTtsFocusRetryAt" to System.currentTimeMillis().toString(),
                    "ttsState" to "focus_denied",
                ),
            )
            if (retry <= MAX_FOCUS_RETRIES) {
                focusRetries[text] = retry
                retryHandler.postDelayed({ speakNextIfNeeded() }, FOCUS_RETRY_DELAY_MS)
            } else {
                diagnosticsRepository?.update(mapOf("ttsState" to "audio_focus_failed"))
            }
            return
        }
        pending.removeFirst()
        focusRetries.remove(text)
        focusRetries.remove("foreground:$text")
        speakNow(text)
    }

    private fun speakNow(text: String) {
        diagnosticsRepository?.update(mapOf("ttsState" to "speak_called"))
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voxnotify-${System.nanoTime()}")
    }

    private fun requestFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
        diagnosticsRepository?.update(mapOf("lastAudioFocusResult" to audioFocusResultName(result)))
        if (BuildConfig.DEBUG) Log.d(TAG, "requestAudioFocus=${audioFocusResultName(result)}")
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun audioFocusResultName(result: Int): String =
        when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> "granted"
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> "delayed"
            AudioManager.AUDIOFOCUS_REQUEST_FAILED -> "failed"
            else -> "unknown:$result"
        }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.abandonAudioFocusRequest(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    companion object {
        private const val TAG = "VoxNotify"
        private const val MAX_PENDING = 5
        private const val MAX_FOCUS_RETRIES = 3
        private const val FOCUS_RETRY_DELAY_MS = 750L
    }
}
