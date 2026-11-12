package org.telegram.messenger.video;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.upstream.DefaultAllocator;

import org.telegram.messenger.DownloadSpeedEstimator;
import org.telegram.messenger.StreamingPolicy;

import java.util.HashMap;

/** Keep Media3's allocation/loading policy and adapt only remote, on-demand playback thresholds. */
@UnstableApi
public final class AdaptiveVideoLoadControl extends DefaultLoadControl {
    private final boolean story;
    private final HashMap<PlayerId, StreamingPolicy.BufferState> buffers = new HashMap<>();
    private final Timeline.Period period = new Timeline.Period();
    private final Timeline.Window window = new Timeline.Window();

    public AdaptiveVideoLoadControl(boolean story) {
        super(new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
                DEFAULT_MIN_BUFFER_MS, DEFAULT_MIN_BUFFER_MS, DEFAULT_MAX_BUFFER_MS, DEFAULT_MAX_BUFFER_MS,
                story ? 1000 : 100, story ? 1000 : 100,
                story ? 1000 : DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                story ? 1000 : DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                DEFAULT_TARGET_BUFFER_BYTES, DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS,
                DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS, DEFAULT_BACK_BUFFER_DURATION_MS,
                DEFAULT_RETAIN_BACK_BUFFER_FROM_KEYFRAME);
        this.story = story;
    }

    @Override
    public void onPrepared(PlayerId playerId) {
        super.onPrepared(playerId);
        buffers.remove(playerId);
    }

    @Override
    public void onReleased(PlayerId playerId) {
        buffers.remove(playerId);
        super.onReleased(playerId);
    }

    @Override
    public boolean shouldStartPlayback(Parameters parameters) {
        if (!isRemoteOnDemand(parameters)) {
            return super.shouldStartPlayback(parameters);
        }
        StreamingPolicy.BufferState state = buffers.get(parameters.playerId);
        if (state == null) {
            state = new StreamingPolicy.BufferState();
            buffers.put(parameters.playerId, state);
        }
        int targetMs = parameters.rebuffering
                ? state.resumeBufferMs(parameters.lastRebufferRealtimeMs)
                : StreamingPolicy.startBufferMs(story, DownloadSpeedEstimator.getBitrate());
        int baseMs = parameters.rebuffering
                ? story ? 1000 : DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
                : story ? 1000 : 100;
        // Scale the measured duration, preserving Media3's playback-speed, allocation-cap and end-of-input handling.
        long adjustedDuration = (long) (parameters.bufferedDurationUs * (double) baseMs / targetMs);
        return super.shouldStartPlayback(new Parameters(parameters.playerId, parameters.timeline,
                parameters.mediaPeriodId, parameters.playbackPositionUs, adjustedDuration, parameters.playbackSpeed,
                parameters.playWhenReady, parameters.rebuffering, parameters.targetLiveOffsetUs,
                parameters.lastRebufferRealtimeMs));
    }

    private boolean isRemoteOnDemand(Parameters parameters) {
        if (parameters.timeline.isEmpty() || parameters.targetLiveOffsetUs != C.TIME_UNSET) {
            return false;
        }
        int index = parameters.timeline.getPeriodByUid(parameters.mediaPeriodId.periodUid, period).windowIndex;
        parameters.timeline.getWindow(index, window);
        MediaItem.LocalConfiguration config = window.mediaItem.localConfiguration;
        if (window.isLive() || config == null) {
            return false;
        }
        String scheme = config.uri.getScheme();
        return "tg".equals(scheme) || "http".equals(scheme) || "https".equals(scheme);
    }
}
