/*
 * Copyright (C) 2026 Dual VoT contributors
 * Licensed under the GNU General Public License v3.0.
 * Adapted for SmartTube.
 */
package com.liskovsoft.smartyoutubetv2.common.utils;

/** Timing policy for Yandex VOT polling and countdown UI. */
public final class YandexVotTiming {
    static final int DEFAULT_POLL_DELAY_SECONDS = 10;
    static final int MAX_POLL_DELAY_SECONDS = 15;

    private YandexVotTiming() { }

    static int pollDelaySeconds(int serverRemainingSeconds) {
        if (serverRemainingSeconds <= 0) return DEFAULT_POLL_DELAY_SECONDS;
        return Math.max(1, Math.min(serverRemainingSeconds, MAX_POLL_DELAY_SECONDS));
    }

    static int estimateOrDefault(int serverRemainingSeconds, int fallbackSeconds) {
        return serverRemainingSeconds > 0 ? serverRemainingSeconds : Math.max(1, fallbackSeconds);
    }

    /** An active deadline can only move earlier; it must never refill the progress ring. */
    static long tightenDeadlineMs(long nowMs, long currentDeadlineMs, int estimateSeconds) {
        long candidate = nowMs + Math.max(1, estimateSeconds) * 1000L;
        return currentDeadlineMs < 0 ? candidate : Math.min(currentDeadlineMs, candidate);
    }

    public static int roundedDisplayMinutes(int seconds) {
        return Math.max(1, (Math.max(1, seconds) + 59) / 60);
    }
}
