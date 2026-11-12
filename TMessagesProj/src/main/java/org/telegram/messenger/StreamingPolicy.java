package org.telegram.messenger;

/** Bounded streaming heuristics. All sizes are bytes and durations are milliseconds. */
public final class StreamingPolicy {
    private static final long KIB = 1024;
    private static final long MIB = 1024 * KIB;

    private StreamingPolicy() {
    }

    public static long mediaBytesPerSecond(long size, long durationMs) {
        return size > 0 && durationMs > 0 ? Math.max(1, (long) (size * 1000.0 / durationMs)) : 0;
    }

    public static long aheadBytes(long size, long durationMs, long networkBitsPerSecond) {
        long networkBytes = Math.max(1, networkBitsPerSecond / 8);
        long mediaBytes = mediaBytesPerSecond(size, durationMs);
        // Aim for 12 seconds of media, but never queue more than three seconds of network work.
        long target = mediaBytes > 0 ? (long) Math.min(8 * MIB, mediaBytes * 12.0) : MIB;
        return (long) Math.max(512 * KIB, Math.min(target, Math.min(8 * MIB, networkBytes * 3.0)));
    }

    public static long urgentBytes(long size, long durationMs) {
        return Math.max(128 * KIB, Math.min(MIB, mediaBytesPerSecond(size, durationMs) / 2));
    }

    public static boolean isDiscontinuity(long previous, long current, long window, int chunkSize) {
        return current < previous - 2L * chunkSize || current > previous + window;
    }

    public static boolean overlapsWindow(long offset, long length, long position, long window) {
        return offset + length > position && offset < position + window;
    }

    public static long candidateOffset(long rangeStart, long rangeEnd, long position, long window, int chunkSize) {
        long aligned = position / chunkSize * chunkSize;
        long candidate = Math.max(aligned, rangeStart);
        return candidate < rangeEnd && candidate < position + window ? candidate : -1;
    }

    public static int startBufferMs(boolean story, long networkBitsPerSecond) {
        return story ? 1000 : networkBitsPerSecond < 3_000_000 ? 750 : 250;
    }

    /** One instance per player, updated only on its playback thread. */
    public static final class BufferState {
        private long lastRebufferAt = Long.MIN_VALUE;
        private int recentRebuffers;

        public int resumeBufferMs(long rebufferAt) {
            if (rebufferAt != lastRebufferAt) {
                recentRebuffers = lastRebufferAt != Long.MIN_VALUE && rebufferAt >= lastRebufferAt
                        && rebufferAt - lastRebufferAt < 60_000 ? Math.min(5, recentRebuffers + 1) : 1;
                lastRebufferAt = rebufferAt;
            }
            return 1000 + 1000 * Math.max(1, recentRebuffers);
        }
    }
}
