package com.houqing.tools.video_compressor_backend.media.handler;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

import static org.assertj.core.api.Assertions.assertThat;

/** Regression for audit M-03: progress used to become Integer.MAX_VALUE when duration was 0. */
class FfmpegProgressProperties {

    @Property(tries = 3000)
    void percentAlwaysWithinRange(@ForAll long micros, @ForAll double duration,
                                  @ForAll @IntRange(min = 0, max = 50) int from,
                                  @ForAll @IntRange(min = 50, max = 100) int to) {
        int p = FfmpegProgress.percent(micros, duration, from, to);
        assertThat(p).isBetween(from, to);
    }

    @Property(tries = 1000)
    void monotonicInPosition(@ForAll @LongRange(min = 0, max = 10_000_000_000L) long a,
                             @ForAll @LongRange(min = 0, max = 10_000_000_000L) long b) {
        long lo = Math.min(a, b), hi = Math.max(a, b);
        assertThat(FfmpegProgress.percent(lo, 600, 0, 100)).isLessThanOrEqualTo(FfmpegProgress.percent(hi, 600, 0, 100));
    }

    @Example
    void zeroDurationDoesNotOverflow() {
        assertThat(FfmpegProgress.percent(5_000_000, 0, 0, 100)).isZero();
        assertThat(FfmpegProgress.percent(5_000_000, Double.NaN, 0, 100)).isZero();
        assertThat(FfmpegProgress.percent(5_000_000, Double.POSITIVE_INFINITY, 0, 100)).isZero();
    }

    @Example
    void parsesBothKeysAndIgnoresNoise() {
        assertThat(FfmpegProgress.outTimeMicros("out_time_us=1500000")).isEqualTo(1_500_000);
        assertThat(FfmpegProgress.outTimeMicros("out_time_ms=1500000")).isEqualTo(1_500_000);
        assertThat(FfmpegProgress.outTimeMicros("out_time_us=N/A")).isEqualTo(-1);
        assertThat(FfmpegProgress.outTimeMicros("out_time_us=-5")).isEqualTo(-1);
        assertThat(FfmpegProgress.outTimeMicros("frame=12")).isEqualTo(-1);
    }
}
