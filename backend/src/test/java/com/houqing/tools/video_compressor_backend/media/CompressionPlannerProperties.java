package com.houqing.tools.video_compressor_backend.media;

import net.jqwik.api.*;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

import static org.assertj.core.api.Assertions.assertThat;

/** Property-based checks of the size-target planner used by both modes. */
class CompressionPlannerProperties {

    @Property(tries = 2000)
    void feasiblePlanNeverExceedsTarget(@ForAll @DoubleRange(min = 1, max = 7200) double duration,
                                        @ForAll @IntRange(min = 160, max = 3840) int width,
                                        @ForAll @IntRange(min = 90, max = 2160) int height,
                                        @ForAll @DoubleRange(min = 10, max = 60) double fps,
                                        @ForAll @LongRange(min = 100_000, max = 4_000_000_000L) long target,
                                        @ForAll @IntRange(min = 0, max = 320) int audioKbps,
                                        @ForAll boolean h265) {
        CompressionPlanner.Plan p = CompressionPlanner.plan(duration, width, height, fps, target, audioKbps, 0, h265);
        if (p.feasible()) {
            double estimated = (p.videoKbps() + audioKbps) * 1000.0 / 8 * duration;
            assertThat(estimated).isLessThanOrEqualTo(target);
            assertThat(p.videoKbps()).isGreaterThanOrEqualTo(CompressionPlanner.MIN_VIDEO_KBPS);
            assertThat(p.suggestions()).isEmpty();
        } else {
            assertThat(p.reason()).isNotBlank();
            assertThat(p.suggestions()).isNotEmpty();
        }
    }

    @Property(tries = 1000)
    void suggestionsNeverUpscale(@ForAll @DoubleRange(min = 1, max = 3600) double duration,
                                 @ForAll @IntRange(min = 320, max = 3840) int width,
                                 @ForAll @IntRange(min = 240, max = 2160) int height,
                                 @ForAll @LongRange(min = 1_000, max = 50_000_000) long target) {
        CompressionPlanner.Plan p = CompressionPlanner.plan(duration, width, height, 30, target, 128, 0, false);
        for (CompressionPlanner.Suggestion s : p.suggestions()) {
            assertThat(s.height()).isLessThanOrEqualTo(height);
        }
    }

    @Property(tries = 1000)
    void minFeasibleSizeIsActuallyFeasible(@ForAll @DoubleRange(min = 1, max = 3600) double duration,
                                           @ForAll @IntRange(min = 320, max = 3840) int width,
                                           @ForAll @IntRange(min = 240, max = 2160) int height,
                                           @ForAll @IntRange(min = 0, max = 256) int audioKbps) {
        CompressionPlanner.Plan probe = CompressionPlanner.plan(duration, width, height, 30, 1, audioKbps, 0, false);
        // asking for the reported minimum (plus rounding slack) must be judged feasible
        CompressionPlanner.Plan atMin = CompressionPlanner.plan(duration, width, height, 30,
                probe.minFeasibleBytes() + 1024, audioKbps, 0, false);
        assertThat(atMin.feasible()).isTrue();
    }

    @Property(tries = 500)
    void invalidInputsAreRejectedNotThrown(@ForAll double duration, @ForAll int w, @ForAll int h, @ForAll long target) {
        Assume.that(duration <= 0 || w <= 0 || h <= 0 || target <= 0 || Double.isNaN(duration));
        CompressionPlanner.Plan p = CompressionPlanner.plan(duration, w, h, 30, target, 128, 0, false);
        assertThat(p.feasible()).isFalse();
    }

    @Example
    void realisticExample1080p() {
        // 60 s of 1080p30 into 20 MB: ~2.5 Mbps video → comfortably watchable
        CompressionPlanner.Plan p = CompressionPlanner.plan(60, 1920, 1080, 30, 20L * 1024 * 1024, 128, 0, false);
        assertThat(p.feasible()).isTrue();
        assertThat(p.videoKbps()).isBetween(2000L, 3000L);
    }

    @Example
    void tinyTargetSuggestsLowerResolution() {
        // 60 s of 1080p30 into 3 MB: too little for 1080p, enough for a lower rung
        CompressionPlanner.Plan p = CompressionPlanner.plan(60, 1920, 1080, 30, 3L * 1024 * 1024, 64, 0, false);
        assertThat(p.feasible()).isFalse();
        assertThat(p.suggestions().get(0).height()).isLessThan(1080);
    }
}
