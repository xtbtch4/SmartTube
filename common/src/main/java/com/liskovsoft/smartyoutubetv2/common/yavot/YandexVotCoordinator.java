/*
 * YaVoT orchestration adapter for SmartTube.
 * Based on the request/poll/upload flow in YaVoT's
 * YandexVoiceOverTranslationPatch (GPL-3.0-or-later).
 */
package com.liskovsoft.smartyoutubetv2.common.yavot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.liskovsoft.smartyoutubetv2.common.prefs.YandexVotData;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coordinates Yandex translation requests and status polling.
 *
 * SmartTube's playback layer supplies the currently selected audio-only
 * stream through AudioSourceProvider. Playback of the returned translated
 * URL remains the responsibility of the player adapter.
 */
public final class YandexVotCoordinator {
    private static final String TAG = "YandexVot";
    private static final int MAX_POLL_ATTEMPTS = 90;

    public interface AudioSourceProvider {
        AudioSource getAudioSource();
    }

    public static final class AudioSource {
        public final String url;
        public final int itag;
        public final long size;

        public AudioSource(String url, int itag, long size) {
            this.url = url;
            this.itag = itag;
            this.size = size;
        }
    }

    public interface Listener {
        void onWaiting(int remainingSeconds);
        void onAudioReady(String audioUrl);
        void onError(String reason);
    }

    private final Context mContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final AtomicLong mGeneration = new AtomicLong();

    public YandexVotCoordinator(Context context) {
        mContext = context.getApplicationContext();
    }

    public void cancel() {
        mGeneration.incrementAndGet();
    }

    public void release() {
        cancel();
        mExecutor.shutdownNow();
    }

    public void translate(final String videoId, final String title,
            final double durationSeconds, final AudioSourceProvider audioProvider,
            final Listener listener) {
        if (videoId == null || videoId.length() == 0 || listener == null) return;
        final long generation = mGeneration.incrementAndGet();
        final YandexVotData prefs = YandexVotData.instance(mContext);
        if (!prefs.isEnabled()) {
            postError(generation, listener, "YaVoT disabled");
            return;
        }

        YandexVotApiClient.configure(prefs.getOAuthToken(), prefs.useLiveVoices(),
                prefs.isAudioProxyEnabled(), prefs.getAudioProxyUrl());

        mExecutor.execute(() -> runTranslation(generation, videoId, title,
                durationSeconds, prefs, audioProvider, listener));
    }

    private void runTranslation(long generation, String videoId, String title,
            double duration, YandexVotData prefs,
            AudioSourceProvider audioProvider, Listener listener) {
        String watchUrl = "https://youtu.be/" + videoId;
        boolean live = prefs.useLiveVoices();
        try {
            YandexVotApiClient.TranslationResult result =
                    YandexVotApiClient.requestTranslation(watchUrl, duration,
                            prefs.getSourceLanguage(), prefs.getTargetLanguage(),
                            title, live);
            if (result == null) {
                postError(generation, listener, "Yandex request failed");
                return;
            }

            for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS && isCurrent(generation); attempt++) {
                int status = result.status();
                if (status == YandexVotApiClient.STATUS_FINISHED
                        || status == YandexVotApiClient.STATUS_PART_CONTENT) {
                    if (result.audioUrl() == null || result.audioUrl().length() == 0) {
                        postError(generation, listener, "Yandex returned an empty audio URL");
                    } else {
                        final String audioUrl = result.audioUrl();
                        mMain.post(() -> {
                            if (isCurrent(generation)) listener.onAudioReady(audioUrl);
                        });
                    }
                    return;
                }

                if (status == YandexVotApiClient.STATUS_FAILED) {
                    postError(generation, listener, result.message() != null
                            ? result.message() : "Yandex translation failed");
                    return;
                }

                if (status == YandexVotApiClient.STATUS_SESSION_REQUIRED) {
                    postError(generation, listener, "Yandex session/OAuth authorization required");
                    return;
                }

                if (status == YandexVotApiClient.STATUS_AUDIO_REQUESTED) {
                    String translationId = result.translationId();
                    AudioSource source = audioProvider != null ? audioProvider.getAudioSource() : null;
                    if (source == null || source.url == null || source.size <= 0) {
                        postError(generation, listener, "No usable audio-only stream from player");
                        return;
                    }
                    boolean uploaded = YandexVotAudioUploader.downloadAndSend(
                            watchUrl, translationId, source.url, source.itag, source.size,
                            new YandexVotAudioUploader.Listener() {
                                @Override public boolean isCancelled() {
                                    return !isCurrent(generation);
                                }
                                @Override public void onProgress(int part, int total) {
                                    postWaiting(generation, listener, 0);
                                }
                            });
                    if (!uploaded) {
                        if (isCurrent(generation)) {
                            YandexVotApiClient.sendEmptyAudio(
                                    watchUrl, translationId, prefs.getOAuthToken());
                        }
                        if (!isCurrent(generation)) return;
                    }
                }

                final int remaining = Math.max(0, result.remainingTime());
                postWaiting(generation, listener, remaining);
                int delay = YandexVotTiming.pollDelaySeconds(remaining);
                try {
                    Thread.sleep(delay * 1000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!isCurrent(generation)) return;
                result = YandexVotApiClient.requestTranslation(watchUrl, duration,
                        prefs.getSourceLanguage(), prefs.getTargetLanguage(),
                        title, live, false);
                if (result == null) {
                    postError(generation, listener, "Yandex polling failed");
                    return;
                }
            }
            if (isCurrent(generation)) postError(generation, listener,
                    "Translation timed out or was cancelled");
        } catch (Exception error) {
            Log.e(TAG, "Translation coordinator failed", error);
            postError(generation, listener, error.getMessage() != null
                    ? error.getMessage() : "Unexpected translation error");
        }
    }

    private boolean isCurrent(long generation) {
        return mGeneration.get() == generation && !Thread.currentThread().isInterrupted();
    }

    private void postWaiting(long generation, Listener listener, int seconds) {
        mMain.post(() -> {
            if (isCurrent(generation)) listener.onWaiting(seconds);
        });
    }

    private void postError(long generation, Listener listener, String reason) {
        mMain.post(() -> {
            if (isCurrent(generation)) listener.onError(reason);
        });
    }
}
