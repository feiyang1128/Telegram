import org.telegram.messenger.StreamingPolicy;

/** Runs with a plain JDK; no Android device or network required. */
public final class StreamingPolicyTest {
    private static final long MIB = 1024 * 1024;

    public static void main(String[] args) {
        // Equal-duration videos with different bitrates get different look-ahead, within the network budget.
        long lowBitrate = StreamingPolicy.aheadBytes(20 * MIB, 120_000, 20_000_000);
        long highBitrate = StreamingPolicy.aheadBytes(80 * MIB, 120_000, 20_000_000);
        check(highBitrate > lowBitrate, "look-ahead must account for media bitrate");
        long slow = StreamingPolicy.aheadBytes(80 * MIB, 120_000, 1_000_000);
        check(slow < highBitrate && slow >= 512 * 1024, "slow networks must reduce speculative work");
        check(StreamingPolicy.aheadBytes(Long.MAX_VALUE, 1, Long.MAX_VALUE) <= 8 * MIB, "large inputs must remain bounded");
        check(StreamingPolicy.aheadBytes(0, 0, 0) >= 512 * 1024, "unknown metadata must not stall playback");
        check(StreamingPolicy.urgentBytes(80 * MIB, 120_000) <= MIB, "urgent bursts must be bounded");

        int chunk = 128 * 1024;
        long position = 10 * MIB + 17;
        // A seek into the middle of a chunk must request that chunk; cached/previously requested ranges are skipped.
        check(StreamingPolicy.candidateOffset(0, 20 * MIB, position, MIB, chunk) == 10 * MIB,
                "seek must align down to include required bytes");
        check(StreamingPolicy.candidateOffset(11 * MIB, 20 * MIB, position, MIB, chunk) == 11 * MIB,
                "download must start at the next missing range");
        check(StreamingPolicy.candidateOffset(12 * MIB, 20 * MIB, position, MIB, chunk) == -1,
                "prefetch must not wrap around to unrelated holes");
        check(StreamingPolicy.candidateOffset(0, 10 * MIB, position, MIB, chunk) == -1,
                "earlier missing data must not displace playback");
        check(StreamingPolicy.overlapsWindow(10 * MIB, chunk, position, MIB), "retain partially consumed chunk");
        check(!StreamingPolicy.overlapsWindow(8 * MIB, chunk, position, MIB), "cancel obsolete seek requests");
        check(!StreamingPolicy.isDiscontinuity(position, position + chunk, MIB, chunk), "normal reads aren't seeks");
        check(StreamingPolicy.isDiscontinuity(position, position + 2 * MIB, MIB, chunk), "detect forward seek");
        check(StreamingPolicy.isDiscontinuity(position, position - MIB, MIB, chunk), "detect backward seek");

        StreamingPolicy.BufferState buffer = new StreamingPolicy.BufferState();
        check(buffer.resumeBufferMs(1000) == 2000, "first stall recovery");
        check(buffer.resumeBufferMs(1000) == 2000, "polling the same stall must not raise the threshold");
        check(buffer.resumeBufferMs(5000) == 3000, "repeated stalls need more buffer");
        for (int i = 1; i <= 20; i++) buffer.resumeBufferMs(5000 + i * 1000);
        check(buffer.resumeBufferMs(25_000) == 6000, "recovery delay must be capped");
        check(buffer.resumeBufferMs(100_000) == 2000, "stable playback must reset escalation");
        check(new StreamingPolicy.BufferState().resumeBufferMs(5000) == 2000, "players must not share stall history");
        check(StreamingPolicy.startBufferMs(false, 1_000_000) > StreamingPolicy.startBufferMs(false, 10_000_000),
                "slow links need more startup buffer");
        System.out.println("Streaming policy tests passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
