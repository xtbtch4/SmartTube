/*
 * YaVoT audio upload transport adapted from
 * xtbtch4/YouTube-VOT-v3 YandexVotAudioDownloader.
 *
 * Original copyright and GPLv3 attribution notices are retained in the
 * YaVoT source repository. This SmartTube adapter intentionally separates
 * stream selection from ranged downloading: SmartTube supplies the selected
 * audio-only URL, itag and size from its own playback stream model.
 */
package com.liskovsoft.smartyoutubetv2.common.yavot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Downloads an audio-only stream in byte ranges and uploads the chunks to
 * the Yandex VOT API. Does not buffer the complete audio file in memory.
 */
public final class YandexVotAudioUploader {
    private static final int CHUNK_SIZE_BYTES = 5_295_308;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final String DOWNLOAD_TYPE = "web_api_steal_sig_and_n";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36";

    public interface Listener {
        boolean isCancelled();
        void onProgress(int currentPart, int totalParts);
    }

    private YandexVotAudioUploader() { }

    /**
     * @param videoUrl canonical YouTube watch URL used by the VOT API
     * @param translationId translation session identifier returned by Yandex
     * @param audioUrl direct URL for a selected audio-only stream
     * @param itag YouTube audio format itag
     * @param fileSize exact stream size in bytes
     */
    public static boolean downloadAndSend(String videoUrl, String translationId,
            String audioUrl, int itag, long fileSize, Listener listener) {
        if (empty(videoUrl) || empty(translationId) || empty(audioUrl)
                || fileSize <= 0 || listener == null || listener.isCancelled()) {
            return false;
        }
        try {
            String fileId = String.format(Locale.US,
                    "{\"downloadType\":\"%s\",\"itag\":%d,\"minChunkSize\":%d,\"fileSize\":\"%d\"}",
                    DOWNLOAD_TYPE, itag, CHUNK_SIZE_BYTES, fileSize);
            int totalParts = (int) ((fileSize + CHUNK_SIZE_BYTES - 1) / CHUNK_SIZE_BYTES);
            if (totalParts <= 0) return false;

            for (int part = 0; part < totalParts; part++) {
                if (listener.isCancelled()) return false;
                listener.onProgress(part + 1, totalParts);
                long start = (long) part * CHUNK_SIZE_BYTES;
                long end = Math.min(fileSize - 1, start + CHUNK_SIZE_BYTES - 1);
                byte[] data = downloadRange(audioUrl, start, end);
                if (listener.isCancelled()) return false;

                boolean sent;
                if (totalParts == 1) {
                    sent = YandexVotApiClient.sendAudio(videoUrl, translationId, fileId, data);
                } else {
                    sent = YandexVotApiClient.sendPartialAudio(
                            videoUrl, translationId, fileId, totalParts, 1, part, data);
                }
                if (!sent) return false;
            }
            return true;
        } catch (Exception error) {
            android.util.Log.e("YandexVot", "Audio download/upload failed", error);
            return false;
        }
    }

    private static byte[] downloadRange(String audioUrl, long start, long end)
            throws IOException {
        long expected = end - start + 1;
        if (expected <= 0 || expected > Integer.MAX_VALUE) {
            throw new IOException("Invalid audio range: " + expected);
        }
        HttpURLConnection connection =
                (HttpURLConnection) new URL(audioUrl).openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Range", "bytes=" + start + "-" + end);
        connection.setRequestProperty("Accept", "*/*");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_PARTIAL
                    && status != HttpURLConnection.HTTP_OK) {
                throw new IOException("Audio range HTTP " + status);
            }
            if (status == HttpURLConnection.HTTP_OK && start > 0) {
                throw new IOException("Server ignored byte range request");
            }
            ByteArrayOutputStream output =
                    new ByteArrayOutputStream((int) Math.min(expected, CHUNK_SIZE_BYTES));
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                long total = 0;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > expected) throw new IOException("Range exceeded expected size");
                    output.write(buffer, 0, count);
                }
            }
            byte[] result = output.toByteArray();
            if (result.length != expected) {
                throw new IOException("Incomplete range: expected " + expected
                        + ", received " + result.length);
            }
            return result;
        } finally {
            connection.disconnect();
        }
    }

    private static boolean empty(String value) {
        return value == null || value.length() == 0;
    }
}
