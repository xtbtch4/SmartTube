/*
 * YaVoT settings port for SmartTube.
 *
 * Derived from YaVoT (GPL-3.0-or-later); original authorship notices are
 * retained in the source project. Adapted for SmartTube's Android app
 * preferences by the SmartTube port.
 */
package com.liskovsoft.smartyoutubetv2.common.prefs;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/** Persistent configuration for the Yandex Voice-over Translation integration. */
public final class YandexVotData {
    private static final String ENABLED = "yavot_enabled";
    private static final String TOKEN = "yavot_oauth_token";
    private static final String SOURCE_LANGUAGE = "yavot_source_language";
    private static final String TARGET_LANGUAGE = "yavot_target_language";
    private static final String LIVE_VOICES = "yavot_live_voices";
    private static final String TRANSLATION_VOLUME = "yavot_translation_volume";
    private static final String ORIGINAL_VOLUME = "yavot_original_volume";
    private static final String AUDIO_PROXY_ENABLED = "yavot_audio_proxy_enabled";
    private static final String AUDIO_PROXY_URL = "yavot_audio_proxy_url";

    private final SharedPreferences mPrefs;

    private YandexVotData(Context context) {
        mPrefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    public static YandexVotData instance(Context context) {
        return new YandexVotData(context);
    }

    public boolean isEnabled() { return mPrefs.getBoolean(ENABLED, true); }
    public void setEnabled(boolean value) { mPrefs.edit().putBoolean(ENABLED, value).apply(); }

    public String getOAuthToken() { return mPrefs.getString(TOKEN, ""); }
    public void setOAuthToken(String value) { mPrefs.edit().putString(TOKEN, value != null ? value.trim() : "").apply(); }

    public String getSourceLanguage() { return mPrefs.getString(SOURCE_LANGUAGE, "auto"); }
    public void setSourceLanguage(String value) { mPrefs.edit().putString(SOURCE_LANGUAGE, value).apply(); }

    public String getTargetLanguage() { return mPrefs.getString(TARGET_LANGUAGE, "ru"); }
    public void setTargetLanguage(String value) { mPrefs.edit().putString(TARGET_LANGUAGE, value).apply(); }

    public boolean useLiveVoices() { return mPrefs.getBoolean(LIVE_VOICES, true); }
    public void setUseLiveVoices(boolean value) { mPrefs.edit().putBoolean(LIVE_VOICES, value).apply(); }

    public int getTranslationVolume() { return clampVolume(mPrefs.getInt(TRANSLATION_VOLUME, 100)); }
    public void setTranslationVolume(int value) { mPrefs.edit().putInt(TRANSLATION_VOLUME, clampVolume(value)).apply(); }

    public int getOriginalVolume() { return clampVolume(mPrefs.getInt(ORIGINAL_VOLUME, 30)); }
    public void setOriginalVolume(int value) { mPrefs.edit().putInt(ORIGINAL_VOLUME, clampVolume(value)).apply(); }

    public boolean isAudioProxyEnabled() { return mPrefs.getBoolean(AUDIO_PROXY_ENABLED, true); }
    public void setAudioProxyEnabled(boolean value) { mPrefs.edit().putBoolean(AUDIO_PROXY_ENABLED, value).apply(); }

    public String getAudioProxyUrl() { return mPrefs.getString(AUDIO_PROXY_URL, "vot-worker.eu.cc"); }
    public void setAudioProxyUrl(String value) { mPrefs.edit().putString(AUDIO_PROXY_URL, value != null ? value.trim() : "").apply(); }

    private static int clampVolume(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
