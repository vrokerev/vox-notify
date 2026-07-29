package com.victormeneses.yape_notifier.storage

import com.victormeneses.yape_notifier.notifications.NativeSettings

class NativeSettingsRepository(private val store: KeyValueStore) {
    fun get(): NativeSettings =
        NativeSettings(
            voiceEnabled = store.getBoolean(KEY_VOICE_ENABLED, true),
            fullPhrase = store.getBoolean(KEY_FULL_PHRASE, true),
            continuousBackground = store.getBoolean(KEY_CONTINUOUS_BACKGROUND, false),
        )

    fun update(settings: NativeSettings): NativeSettings {
        store.putBoolean(KEY_VOICE_ENABLED, settings.voiceEnabled)
        store.putBoolean(KEY_FULL_PHRASE, settings.fullPhrase)
        store.putBoolean(KEY_CONTINUOUS_BACKGROUND, settings.continuousBackground)
        return get()
    }

    companion object {
        private const val KEY_VOICE_ENABLED = "voice_enabled"
        private const val KEY_FULL_PHRASE = "full_phrase"
        private const val KEY_CONTINUOUS_BACKGROUND = "continuous_background"
    }
}
