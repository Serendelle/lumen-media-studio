package com.houqing.tools.video_compressor_backend.media;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure arithmetic for "compress to a target size": derives the video bitrate a size target implies and judges
 * whether the result is watchable, using bits-per-pixel-per-frame thresholds. No I/O, so it is property-tested.
 */
public final class CompressionPlanner {
    private CompressionPlanner() {}

    /** Below these bits-per-pixel-per-frame values the picture falls apart (blocking/smearing). */
    static final double MIN_BPP_H264 = 0.015;
    static final double MIN_BPP_H265 = 0.010;
    /** Container overhead headroom kept out of the budget. */
    static final double CONTAINER_HEADROOM = 0.98;
    static final long MIN_VIDEO_KBPS = 50;
    private static final int[] LADDER = {2160, 1440, 1080, 720, 540, 480, 360};

    public record Plan(boolean feasible, long videoKbps, int audioKbps, int height, double bpp,
                       long minFeasibleBytes, String reason, List<Suggestion> suggestions) {}

    public record Suggestion(int height, long videoKbps, String note) {}

    /**
     * @param durationSec  real duration from ffprobe
     * @param srcW,srcH    source resolution
     * @param fps          source (or requested) frame rate
     * @param targetBytes  desired output size
     * @param audioKbps    audio bitrate that will be encoded (0 = no audio)
     * @param targetHeight requested output height (0 = keep source)
     * @param h265         codec family
     */
    public static Plan plan(double durationSec, int srcW, int srcH, double fps, long targetBytes,
                            int audioKbps, int targetHeight, boolean h265) {
        if (durationSec <= 0 || srcW <= 0 || srcH <= 0 || targetBytes <= 0) {
            return new Plan(false, 0, audioKbps, srcH, 0, 0, "缺少有效的时长或分辨率信息", List.of());
        }
        double effFps = fps > 0 && fps <= 240 ? fps : 30;
        int outH = targetHeight > 0 && targetHeight < srcH ? targetHeight : srcH;
        int outW = scaledWidth(srcW, srcH, outH);
        double minBpp = h265 ? MIN_BPP_H265 : MIN_BPP_H264;

        double audioBytes = audioKbps * 1000.0 / 8 * durationSec;
        double videoBudget = targetBytes * CONTAINER_HEADROOM - audioBytes;
        long videoKbps = videoBudget <= 0 ? 0 : (long) (videoBudget * 8 / durationSec / 1000);
        double bpp = videoKbps * 1000.0 / ((double) outW * outH * effFps);
        long minBytes = minFeasibleBytes(durationSec, outW, outH, effFps, audioKbps, minBpp);

        List<Suggestion> suggestions = new ArrayList<>();
        if (videoKbps >= MIN_VIDEO_KBPS && bpp >= minBpp) {
            return new Plan(true, videoKbps, audioKbps, outH, round(bpp), minBytes, null, suggestions);
        }
        // not feasible at this resolution: find the highest lower rung that works with the same budget
        for (int h : LADDER) {
            if (h >= outH) continue;
            int w = scaledWidth(srcW, srcH, h);
            double rungBpp = videoKbps * 1000.0 / ((double) w * h * effFps);
            if (videoKbps >= MIN_VIDEO_KBPS && rungBpp >= minBpp) {
                suggestions.add(new Suggestion(h, videoKbps, "降低到 " + h + "p 可在该体积内保持可接受画质"));
                break;
            }
        }
        suggestions.add(new Suggestion(outH, (long) (minBytes * 8 / durationSec / 1000),
                "保持当前分辨率至少需要约 " + Math.max(1, Math.round(minBytes / 1048576.0)) + " MB"));
        String reason = videoKbps <= 0 ? "目标体积连音频都放不下" : "目标体积过小，当前分辨率下画质将不可接受";
        return new Plan(false, videoKbps, audioKbps, outH, round(bpp), minBytes, reason, suggestions);
    }

    static long minFeasibleBytes(double durationSec, int w, int h, double fps, int audioKbps, double minBpp) {
        double videoBps = Math.max(MIN_VIDEO_KBPS * 1000.0, minBpp * w * h * fps);
        double total = (videoBps + audioKbps * 1000.0) / 8 * durationSec / CONTAINER_HEADROOM;
        return (long) Math.ceil(total);
    }

    static int scaledWidth(int srcW, int srcH, int outH) {
        int w = (int) Math.round(srcW * (double) outH / srcH);
        return Math.max(2, w - (w % 2));
    }

    private static double round(double v) { return Math.round(v * 10000) / 10000.0; }
}
