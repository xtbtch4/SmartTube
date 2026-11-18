package com.liskovsoft.smartyoutubetv2.common.exoplayer.controller;

import android.content.Context;
import android.os.Build;
import android.os.Build.VERSION;
import android.os.Handler;
import android.os.Looper;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.liskovsoft.smartyoutubetv2.common.prefs.YandexVotData;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.MergingMediaSource;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.sabr.SabrMediaSource;
import com.google.android.exoplayer2.source.sabr.manifest.SabrManifest;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.BuildConfig;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.listener.PlayerEventListener;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.LiveStateProvider;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.ExoMediaSourceFactory;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.errors.TrackErrorFixer;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.VolumeBooster;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.ExoFormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackInfoFormatter2;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackSelectorManager;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackSelectorUtil;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.MediaTrack;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.VideoTrack;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.ExoUtils;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.common.yavot.YandexVotCoordinator;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.List;

public class ExoPlayerController implements Player.EventListener, LiveStateProvider {
    private static final String TAG = ExoPlayerController.class.getSimpleName();
    /** How far from the live edge (in segments) the playback is considered to be on the edge */
    private static final int SABR_LIVE_EDGE_TOLERANCE_SEGMENTS = 4;
    private final Context mContext;
    private final ExoMediaSourceFactory mMediaSourceFactory;
    private final TrackSelectorManager mTrackSelectorManager;
    private final TrackInfoFormatter2 mTrackFormatter;
    private boolean mOnSourceChanged;
    private WeakReference<Video> mVideo;
    private final PlayerEventListener mEventListener;
    private SimpleExoPlayer mPlayer;
    /** Not null only for sabr live streams */
    private SabrManifest mSabrLiveManifest;
    /** Sabr live stream without DVR: rewind is forbidden, the playback must always stay on the live edge */
    private boolean mSabrLiveSeekBlocked;
    /** Sabr live stream is waiting for the live start position (see {@link #seekToSabrLiveStart()}) */
    private boolean mSabrLiveStartPending;
    private PlayerView mPlayerView;
    private VolumeBooster mVolumeBooster;
    private boolean mIsEnded;
    private Runnable mOnVideoLoaded;
    private YandexVotCoordinator mYandexVotCoordinator;
    private YandexVotCoordinator.AudioSource mYandexVotAudioSource;
    private SimpleExoPlayer mYandexVotPlayer;
    private float mYandexVotBaseVolume = 1f;
    private final Handler mYandexVotHandler = new Handler(Looper.getMainLooper());
    private final Runnable mYandexVotSync = new Runnable() {
        @Override public void run() {
            if (mYandexVotPlayer == null || mPlayer == null) return;
            long videoPosition = mPlayer.getCurrentPosition();
            long audioPosition = mYandexVotPlayer.getCurrentPosition();
            if (Math.abs(videoPosition - audioPosition) > 500) {
                mYandexVotPlayer.seekTo(videoPosition);
            }
            mYandexVotPlayer.setPlayWhenReady(mPlayer.getPlayWhenReady());
            PlaybackParameters parameters = mPlayer.getPlaybackParameters();
            if (parameters != null) mYandexVotPlayer.setPlaybackParameters(parameters);
            mYandexVotHandler.removeCallbacks(mYandexVotSync);
            mYandexVotHandler.post(mYandexVotSync);
            PlaybackParameters parameters = mPlayer.getPlaybackParameters();
            if (parameters != null) {
                mYandexVotPlayer.setPlaybackParameters(parameters);
            }
            mYandexVotHandler.postDelayed(this, 500);
        }
    };

    public ExoPlayerController(Context context, PlayerEventListener eventListener) {
        PlayerTweaksData playerTweaksData = PlayerTweaksData.instance(context);
        mContext = context.getApplicationContext();
        mTrackSelectorManager = new TrackSelectorManager(context);
        mMediaSourceFactory = new ExoMediaSourceFactory(context);
        mTrackFormatter = new TrackInfoFormatter2();
        mTrackFormatter.enableBitrate(PlayerTweaksData.instance(context).isQualityInfoBitrateEnabled());

        mMediaSourceFactory.setTrackErrorFixer(new TrackErrorFixer(mTrackSelectorManager));
        mEventListener = eventListener;
        
        applyShield720pFix();
        VideoTrack.sIsAltPresetsEnabled = playerTweaksData.isAltPresetsEnabled();
        MediaTrack.setAvcOverVp9Preferred(playerTweaksData.isAvcOverVp9Preferred());
    }

    private void applyShield720pFix() {
        PlayerData playerData = PlayerData.instance(mContext);
        mTrackSelectorManager.selectTrack(FormatItem.toMediaTrack(playerData.getFormat(FormatItem.TYPE_VIDEO)));
        mTrackSelectorManager.selectTrack(FormatItem.toMediaTrack(playerData.getFormat(FormatItem.TYPE_AUDIO)));
        mTrackSelectorManager.selectTrack(FormatItem.toMediaTrack(playerData.getFormat(FormatItem.TYPE_SUBTITLE)));
    }

    public void setYandexVotAudioSource(MediaItemFormatInfo formatInfo) {
        mYandexVotAudioSource = null;
        if (formatInfo == null || formatInfo.getAdaptiveFormats() == null) {
            return;
        }

        MediaFormat selected = null;
        long selectedBitrate = -1;
        for (MediaFormat format : formatInfo.getAdaptiveFormats()) {
            if (format == null || format.getFormatType() != MediaFormat.FORMAT_TYPE_DASH || format.getMimeType() == null
                    || !format.getMimeType().toLowerCase(java.util.Locale.US).startsWith("audio/")
                    || format.getUrl() == null || format.getUrl().length() == 0) {
                continue;
            }
            long size = parsePositiveLong(format.getClen());
            if (size <= 0) {
                continue;
            }
            long bitrate = parsePositiveLong(format.getBitrate());
            if (selected == null || bitrate > selectedBitrate) {
                selected = format;
                selectedBitrate = bitrate;
            }
        }

        if (selected != null) {
            int itag = (int) parsePositiveLong(selected.getITag());
            long size = parsePositiveLong(selected.getClen());
            mYandexVotAudioSource = new YandexVotCoordinator.AudioSource(
                    selected.getUrl(), itag, size);
        }
    }

    private long parsePositiveLong(String value) {
        if (value == null) return -1;
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    public void startYandexVot(YandexVotCoordinator.Listener listener) {
        final Video video = getVideo();
        if (video == null || video.videoId == null || video.videoId.length() == 0) {
            if (listener != null) listener.onError("No video is selected");
            return;
        }
        if (mYandexVotAudioSource == null) {
            if (listener != null) listener.onError("No audio-only DASH format is available");
            return;
        }
        if (mYandexVotCoordinator == null) {
            mYandexVotCoordinator = new YandexVotCoordinator(mContext);
        }
        final YandexVotCoordinator.AudioSource source = mYandexVotAudioSource;
        mYandexVotCoordinator.translate(video.videoId, video.title,
                video.getDurationMs() > 0 ? video.getDurationMs() / 1000.0 : 0,
                () -> source, new YandexVotCoordinator.Listener() {
                    @Override public void onWaiting(int remainingSeconds) {
                        if (listener != null) listener.onWaiting(remainingSeconds);
                    }
                    @Override public void onAudioReady(String audioUrl) {
                        playYandexVotAudio(audioUrl);
                        if (listener != null) listener.onAudioReady(audioUrl);
                    }
                    @Override public void onError(String reason) {
                        if (listener != null) listener.onError(reason);
                    }
                });
    }

    public void cancelYandexVot() {
        if (mYandexVotCoordinator != null) {
            mYandexVotCoordinator.cancel();
        }
        stopYandexVotAudio();
    }

    private void playYandexVotAudio(String audioUrl) {
        if (audioUrl == null || audioUrl.isEmpty() || mPlayer == null) return;
        stopYandexVotAudio();
        try {
            mYandexVotPlayer = ExoPlayerFactory.newSimpleInstance(
                    mContext, new DefaultRenderersFactory(mContext), new DefaultTrackSelector());
            mYandexVotPlayer.setVolume(YandexVotData.instance(mContext).getTranslationVolume() / 100f);
            mYandexVotPlayer.prepare(mMediaSourceFactory.fromDirectUrl(audioUrl));
            mYandexVotPlayer.seekTo(Math.max(0, mPlayer.getCurrentPosition()));
            mYandexVotPlayer.setPlayWhenReady(mPlayer.getPlayWhenReady());
            mYandexVotBaseVolume = mPlayer.getVolume();
            mPlayer.setVolume(mYandexVotBaseVolume *
                    YandexVotData.instance(mContext).getOriginalVolume() / 100f);
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to start YaVoT audio", error);
            stopYandexVotAudio();
        }
    }

    private void stopYandexVotAudio() {
        mYandexVotHandler.removeCallbacks(mYandexVotSync);
        if (mYandexVotPlayer != null) {
            try {
                mYandexVotPlayer.stop();
                mYandexVotPlayer.release();
            } catch (RuntimeException error) {
                Log.e(TAG, "Unable to release YaVoT audio", error);
            }
            mYandexVotPlayer = null;
        }
        if (mPlayer != null) {
            mPlayer.setVolume(mYandexVotBaseVolume);
        }
    }

    public void openSabr(MediaItemFormatInfo formatInfo) {
        MediaSource mediaSource = mMediaSourceFactory.fromSabrFormatInfo(formatInfo);
        openMediaSource(mediaSource);

        if (formatInfo.isLive()) {
            SabrManifest manifest = ((SabrMediaSource) mediaSource).getManifest();
            mSabrLiveManifest = manifest;
            // NOTE: DVR flag can be ignored. Streams are rewindable in practice even without it
            //mSabrLiveSeekBlocked = !formatInfo.isLiveDvrEnabled();
            mSabrLiveStartPending = true;
        }
    }

    public void openDash(MediaItemFormatInfo formatInfo) {
        MediaSource mediaSource = mMediaSourceFactory.fromDashFormatInfo(formatInfo);
        openMediaSource(mediaSource);
    }

    public void openDash(InputStream dashManifest) {
        MediaSource mediaSource = mMediaSourceFactory.fromDashManifest(dashManifest);
        openMediaSource(mediaSource);
    }

    public void openDashUrl(String dashManifestUrl) {
        MediaSource mediaSource = mMediaSourceFactory.fromDashManifestUrl(dashManifestUrl);
        openMediaSource(mediaSource);
    }

    public void openHlsUrl(String hlsPlaylistUrl) {
        MediaSource mediaSource = mMediaSourceFactory.fromHlsPlaylist(hlsPlaylistUrl);
        openMediaSource(mediaSource);
    }

    public void openUrlList(List<String> urlList) {
        MediaSource mediaSource = mMediaSourceFactory.fromUrlList(urlList);
        openMediaSource(mediaSource);
    }

    public void openMerged(MediaItemFormatInfo formatInfo, String hlsPlaylistUrl) {
        MediaSource dashMediaSource = mMediaSourceFactory.fromDashFormatInfo(formatInfo);
        MediaSource hlsMediaSource = mMediaSourceFactory.fromHlsPlaylist(hlsPlaylistUrl);
        openMediaSource(new MergingMediaSource(dashMediaSource, hlsMediaSource));
    }

    public void openMerged(InputStream dashManifest, String hlsPlaylistUrl) {
        MediaSource dashMediaSource = mMediaSourceFactory.fromDashManifest(dashManifest);
        MediaSource hlsMediaSource = mMediaSourceFactory.fromHlsPlaylist(hlsPlaylistUrl);
        openMediaSource(new MergingMediaSource(dashMediaSource, hlsMediaSource));
    }

    /**
     * Rewind is forbidden (live stream without DVR)
     */
    @Override
    public boolean isSeekBlocked() {
        return mSabrLiveSeekBlocked;
    }

    /**
     * Sabr timeline is static (fake long duration). The real end of the live stream is the live edge.
     * @return live edge position or -1 if not a sabr live stream (or unknown yet)
     */
    @Override
    public long getLiveDurationMs() {
        long liveEdgeMs = mSabrLiveManifest != null ? mSabrLiveManifest.getLiveEdgeMs() : -1;
        // Skip the fallback duration because SABR uses absolute values for clipped streams
        if (mSabrLiveManifest != null && !getVideo().isFullLive()) {
            return liveEdgeMs;
        }
        // NOTE: liveEdgeMs could be -1 during initial video loading. Use fallback duration.
        return liveEdgeMs != -1 ? liveEdgeMs : getVideo() != null ? getVideo().getLiveDurationMs() : -1;
    }

    @Override
    public long getLiveStartMs() {
        return mSabrLiveManifest != null ? Math.max(mSabrLiveManifest.getLiveWindowStartMs(), 0) : 0;
    }

    private void openMediaSource(MediaSource mediaSource) {
        cancelYandexVot();
        mSabrLiveManifest = null; // set by the sabr live only
        mSabrLiveSeekBlocked = false;
        mSabrLiveStartPending = false;
        resetPlayerState(); // fixes occasional video artifacts and problems with quality switching
        setQualityInfo("");

        mTrackSelectorManager.setMergedSource(mediaSource instanceof MergingMediaSource);
        mTrackSelectorManager.invalidate();
        mOnSourceChanged = true;
        mEventListener.onSourceChanged(getVideo());
        mPlayer.prepare(mediaSource);
    }

    public long getPositionMs() {
        if (mPlayer == null) {
            return -1;
        }

        return mPlayer.getCurrentPosition();
    }

    /**
     * NOTE: Pos gathered from content block data may slightly exceed video duration
     * (e.g. 302200 when duration is 302000).
     */
    public void setPositionMs(long positionMs) {
        if (mSabrLiveSeekBlocked) {
            long liveEdgeMs = getLiveDurationMs();
            long toleranceMs = SABR_LIVE_EDGE_TOLERANCE_SEGMENTS * mSabrLiveManifest.getLiveSegmentDurationMs();
            // Skip the seek when already near the edge (the app sets position periodically)
            if (liveEdgeMs != -1 && mPlayer != null && liveEdgeMs - mPlayer.getCurrentPosition() > toleranceMs) {
                Log.d(TAG, "Live stream has no DVR. Moving to the live edge: " + liveEdgeMs);
                mPlayer.seekTo(liveEdgeMs);
            }
            return;
        }

        // NOTE: Url list videos at load stage has undefined (-1) duration
        // NOTE: Avoid to check live streams duration here as ones may have undefined (-1) duration on loading stage.
        if (mPlayer != null && positionMs >= 0) {
            mPlayer.seekTo(positionMs);
            if (mYandexVotPlayer != null) mYandexVotPlayer.seekTo(positionMs);
            // When we are dealing with SABR live that doesn't exceed 24 hours in length, we can set the duration right here
            mSabrLiveStartPending = false;
        }
    }

    public long getDurationMs() {
        if (mPlayer == null) {
            return -1;
        }

        long duration = mPlayer.getDuration();

        if (duration == C.TIME_UNSET) {
            duration = -1;
        }

        if (duration != -1 && duration <= Video.MAX_LIVE_DURATION_MS) {
            return duration;
        }

        long liveDurationMs = getLiveDurationMs();

        return liveDurationMs != -1 ? liveDurationMs : duration;
    }

    public void setPlayWhenReady(boolean play) {
        if (mPlayer != null) {
            mPlayer.setPlayWhenReady(play);
            if (mYandexVotPlayer != null) mYandexVotPlayer.setPlayWhenReady(play);
        }
    }

    public boolean getPlayWhenReady() {
        if (mPlayer == null) {
            return false;
        }

        return mPlayer.getPlayWhenReady();
    }

    public boolean isPlaying() {
        return ExoUtils.isPlaying(mPlayer);
    }
    
    public boolean isLoading() {
        return ExoUtils.isLoading(mPlayer);
    }

    public boolean isBuffering() {
        return ExoUtils.isBuffering(mPlayer);
    }
    
    public boolean containsMedia() {
        if (mPlayer == null) {
            return false;
        }

        return mPlayer.getPlaybackState() != Player.STATE_IDLE;
    }
    
    public void release() {
        mTrackSelectorManager.release();
        mMediaSourceFactory.release();
        releasePlayer();
        mPlayerView = null;
        // Don't destroy it (needed inside the bridge)!
        //mEventListener = null;
    }
    
    public void setPlayer(SimpleExoPlayer player) {
        mPlayer = player;
        player.addListener(this);
    }

    //@Override
    //public void setEventListener(PlayerEventListener eventListener) {
    //    mEventListener = eventListener;
    //}
    
    public void setPlayerView(PlayerView playerView) {
        mPlayerView = playerView;
    }
    
    public void setTrackSelector(DefaultTrackSelector trackSelector) {
        mTrackSelectorManager.setTrackSelector(trackSelector);

        if (mContext != null && trackSelector != null && PlayerTweaksData.instance(mContext).isTunneledPlaybackEnabled()) {
            // Enable tunneling if supported by the current media and device configuration.
            if (VERSION.SDK_INT >= 21) {
                trackSelector.setParameters(trackSelector.buildUponParameters().setTunnelingAudioSessionId(C.generateAudioSessionIdV21(mContext)));
            }
        }
    }
    
    public void setVideo(Video video) {
        mVideo = new WeakReference<>(video);
    }
    
    public Video getVideo() {
        return mVideo != null ? mVideo.get() : null;
    }
    
    public List<FormatItem> getVideoFormats() {
        return ExoFormatItem.from(mTrackSelectorManager.getVideoTracks());
    }
    
    public List<FormatItem> getAudioFormats() {
        return ExoFormatItem.from(mTrackSelectorManager.getAudioTracks());
    }
    
    public List<FormatItem> getSubtitleFormats() {
        return ExoFormatItem.from(mTrackSelectorManager.getSubtitleTracks());
    }
    
    public void selectFormat(FormatItem formatItem) {
        if (formatItem != null && formatItem.getTrack() != null) {
            FormatItem selectedFormatItem = getSelectedFormat(formatItem.getTrack().rendererIndex);
            if (!formatItem.equals(selectedFormatItem)) {
                mTrackSelectorManager.selectTrack(formatItem.getTrack());
                mEventListener.onTrackSelected(formatItem);
            }
        }
    }

    public FormatItem getVideoFormat() {
        return getSelectedFormat(TrackSelectorManager.RENDERER_INDEX_VIDEO);
    }

    public FormatItem getAudioFormat() {
        return getSelectedFormat(TrackSelectorManager.RENDERER_INDEX_AUDIO);
    }

    public FormatItem getSubtitleFormat() {
        return getSelectedFormat(TrackSelectorManager.RENDERER_INDEX_SUBTITLE);
    }

    private FormatItem getSelectedFormat(int rendererIndex) {
        return ExoFormatItem.from(mTrackSelectorManager.getSelectedTrack(rendererIndex));
    }

    @Override
    public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
        Log.d(TAG, "onTracksChanged: start: groups length: " + trackGroups.length);

        if (trackGroups.length == 0) {
            Log.i(TAG, "onTracksChanged: Hmm. Strange. Received empty groups, no selections. Why is this happens only on next/prev videos?");
            return;
        }

        notifyOnVideoLoad();

        for (TrackSelection selection : trackSelections.getAll()) {
            if (selection != null) {
                // EXO: 2.12.1
                //Format format = selection.getSelectedFormat();

                // EXO: 2.13.1
                Format format = selection.getFormat(0);

                mEventListener.onTrackChanged(ExoFormatItem.from(format));

                mTrackFormatter.setFormat(format);
            }
        }
        
        setQualityInfo(mTrackFormatter.getQualityLabel());

        // Manage audio focus. E.g. use Spotify when audio is disabled. (NOT NEEDED!!!)
        //MediaTrack audioTrack = mTrackSelectorManager.getAudioTrack();
        //ExoPlayerInitializer.enableAudioFocus(mPlayer, audioTrack != null && !audioTrack.isEmpty());
    }

    private void notifyOnVideoLoad() {
        if (mOnSourceChanged) {
            mOnSourceChanged = false;

            mEventListener.onVideoLoaded(getVideo());

            if (mOnVideoLoaded != null) {
                mOnVideoLoaded.run();
            }

            // Produce thread sync problems
            // Attempt to read from field 'java.util.TreeMap$Node java.util.TreeMap$Node.left' on a null object reference
            //mTrackSelectorManager.fixTracksSelection();
        }
    }

    @Override
    public void onPlayerError(ExoPlaybackException error) {
        Log.e(TAG, "onPlayerError: " + error);

        // NOTE: Player is released at this point. So, there is no sense to restore the playback here.

        Throwable nested = error.getCause() != null ? error.getCause() : error;

        mEventListener.onEngineError(error.type, error.rendererIndex, nested);
    }

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onPlayerStateChanged: " + TrackSelectorUtil.stateToString(playbackState));
        }

        boolean isPlayPressed = Player.STATE_READY == playbackState && playWhenReady;
        boolean isPausePressed = Player.STATE_READY == playbackState && !playWhenReady;
        boolean isPlaybackEnded = Player.STATE_ENDED == playbackState && playWhenReady;
        boolean isBuffering = Player.STATE_BUFFERING == playbackState && playWhenReady;

        if (Player.STATE_READY == playbackState && mSabrLiveStartPending) {
            seekToSabrLiveStart();
        }

        // Fix chapters (seek and play) after playback ends
        if (isPlaybackEnded && mIsEnded) {
            return;
        }

        if (isPlayPressed) {
            mEventListener.onPlay();
        } else if (isPausePressed) {
            mEventListener.onPause();
        } else if (isPlaybackEnded) {
            mEventListener.onPlayEnd();
            mIsEnded = true;
        } else if (isBuffering) {
            mEventListener.onBuffering();
        }

        if (getPositionMs() < getDurationMs()) {
            mIsEnded = false;
        }
    }

    @Override
    public void onPositionDiscontinuity(int reason) {
        Log.e(TAG, "onPositionDiscontinuity");

        // Fix video loop on 480p with legacy codes enabled
        if (reason == Player.DISCONTINUITY_REASON_PERIOD_TRANSITION) {
            mPlayer.stop();
            mEventListener.onPlayEnd();
        }
    }

    @Override
    public void onSeekProcessed() {
        mEventListener.onSeekEnd();
    }

    public float getSpeed() {
        if (mPlayer != null) {
            return mPlayer.getPlaybackParameters().speed;
        } else {
            return -1;
        }
    }

    public void setSpeed(float speed) {
        if (mPlayer != null && speed > 0) {
            if (PlayerTweaksData.instance(mContext).isAudioTimeStretchingEnabled()) {
                mPlayer.setPlaybackParameters(new PlaybackParameters(speed, mPlayer.getPlaybackParameters().pitch));
            } else {
                mPlayer.setPlaybackParameters(new PlaybackParameters(speed, speed));
            if (mYandexVotPlayer != null) mYandexVotPlayer.setPlaybackParameters(new PlaybackParameters(speed, speed));
            }

            mTrackFormatter.setSpeed(speed);
            setQualityInfo(mTrackFormatter.getQualityLabel());
            mEventListener.onSpeedChanged(speed);
        }
    }

    public float getPitch() {
        if (mPlayer != null) {
            return mPlayer.getPlaybackParameters().pitch;
        } else {
            return -1;
        }
    }

    public void setPitch(float pitch) {
        if (mPlayer != null && pitch > 0) {
            mPlayer.setPlaybackParameters(new PlaybackParameters(mPlayer.getPlaybackParameters().speed, pitch));
        }
    }

    public void setVolume(float volume) {
        if (mPlayer != null && volume >= 0) {
            mPlayer.setVolume(Math.min(volume, 1f));

            //applyVolumeBoost(volume);
        }
    }
    
    public float getVolume() {
        if (mPlayer != null) {
            return mPlayer.getVolume();
        } else {
            return 1;
        }
    }

    /**
     * Fixes video artifacts when switching to the next video.<br/>
     * Also could help with memory leaks(??)<br/>
     * Without this also you'll have problems with track quality switching(??).
     */
    public void resetPlayerState() {
        if (containsMedia()) {
            mPlayer.stop(true);
        }
    }
    
    public void setOnVideoLoaded(Runnable onVideoLoaded) {
        mOnVideoLoaded = onVideoLoaded;
    }

    private void setQualityInfo(String qualityInfoStr) {
        if (mPlayerView != null && qualityInfoStr != null) {
            mPlayerView.setQualityInfo(qualityInfoStr);
        }
    }

    private void applyVolumeBoost(float volume) {
        if (mPlayer == null) {
            return;
        }

        if (mVolumeBooster != null) {
            mPlayer.removeAudioListener(mVolumeBooster);
            mVolumeBooster = null;
        }

        // 5.1 audio cannot be boosted (format isn't supported error)
        // also, other 2.0 tracks in 5.1 group is already too loud. so cancel them too.
        if (volume > 1f && !contains51Audio() && Build.VERSION.SDK_INT >= 19) {
            mVolumeBooster = new VolumeBooster(true, volume, null);
            mPlayer.addAudioListener(mVolumeBooster);
        }
    }
    
    private boolean contains51Audio() {
        if (mTrackSelectorManager == null || mTrackSelectorManager.getAudioTracks() == null) {
            return false;
        }

        for (MediaTrack track : mTrackSelectorManager.getAudioTracks()) {
            if (TrackSelectorUtil.is51Audio(track.format)) {
                return true;
            }
        }

        return false;
    }

    private void releasePlayer() {
        stopYandexVotAudio();
        if (mYandexVotCoordinator != null) {
            mYandexVotCoordinator.release();
            mYandexVotCoordinator = null;
        }
        mYandexVotAudioSource = null;
        if (mPlayer == null) {
            return;
        }

        try {
            mPlayer.removeListener(this);
            mPlayer.stop(true); // Cause input lags due to high cpu load?
            mPlayer.clearVideoSurface();
            mPlayer.release();
        } catch (ArrayIndexOutOfBoundsException e) { // thrown on stop()
            e.printStackTrace();
        } finally {
            mPlayer = null;
        }
    }

    /**
     * Live head position comes with the first server response (the same one that makes the player ready).
     * So jump near the live edge as soon as the position is known.
     */
    private void seekToSabrLiveStart() {
        if (mSabrLiveManifest == null || mPlayer == null) {
            return;
        }

        // Stream without DVR can't be rewound: start right on the live edge
        long startPositionMs = mSabrLiveSeekBlocked ? getLiveDurationMs() : mSabrLiveManifest.getLiveStartPositionMs();

        if (startPositionMs == -1) { // not yet. Try on the next event
            return;
        }

        mSabrLiveStartPending = false;
        mPlayer.seekTo(startPositionMs);
    }
}
