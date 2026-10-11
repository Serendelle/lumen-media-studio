package com.houqing.tools.video_compressor_backend.media.handler;

import com.houqing.tools.video_compressor_backend.media.TaskContext;

import java.util.function.Consumer;

/**
 * Parses FFmpeg's `-progress pipe:1` stream into a percentage of the real, server-probed duration, mapped onto
 * [from, to] so multi-pass jobs share one honest progress bar.
 */
public final class FfmpegProgress {
    private FfmpegProgress() {}

    public static Consumer<String> lineConsumer(TaskContext ctx, double totalSeconds, String stage) {
        return lineConsumer(ctx, totalSeconds, 0, 100, stage);
    }

    public static Consumer<String> lineConsumer(TaskContext ctx, double totalSeconds, int from, int to, String stage) {
        return line -> {
            long us = outTimeMicros(line);
            if (us >= 0) ctx.progress(percent(us, totalSeconds, from, to), stage);
        };
    }

    /** -1 when the line carries no position. */
    static long outTimeMicros(String line) {
        String s = line.trim();
        String v;
        if (s.startsWith("out_time_us=")) v = s.substring("out_time_us=".length());
        else if (s.startsWith("out_time_ms=")) v = s.substring("out_time_ms=".length()); // microseconds despite the name
        else return -1;
        try {
            long us = Long.parseLong(v.trim());
            return us < 0 ? -1 : us;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Always within [from, to], even for zero/garbage durations (the original code produced Integer.MAX_VALUE). */
    static int percent(long micros, double totalSeconds, int from, int to) {
        if (!(totalSeconds > 0) || Double.isInfinite(totalSeconds)) return from;
        double fraction = Math.min(1.0, Math.max(0.0, micros / (totalSeconds * 1_000_000.0)));
        return from + (int) Math.round(fraction * (to - from));
    }
}
