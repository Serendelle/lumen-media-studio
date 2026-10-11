package com.houqing.tools.video_compressor_backend.media.handler;

import com.houqing.tools.video_compressor_backend.media.Tool;
import com.houqing.tools.video_compressor_backend.media.ToolLocator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Shared FFmpeg argument conventions: never prompt, and inputs may only be read from local files. */
public final class FfmpegCommands {
    private FfmpegCommands() {}

    public static List<String> start(ToolLocator tools, int threads) {
        List<String> cmd = new ArrayList<>(tools.command(Tool.FFMPEG));
        cmd.add("-hide_banner");
        cmd.add("-nostdin");
        cmd.add("-y");
        if (threads > 0) {
            cmd.add("-threads");
            cmd.add(String.valueOf(threads));
        }
        return cmd;
    }

    /** Adds an input restricted to the file protocol, so a crafted file cannot make FFmpeg open other resources. */
    public static void input(List<String> cmd, Path file) {
        cmd.add("-protocol_whitelist");
        cmd.add("file");
        cmd.add("-i");
        cmd.add(file.toString());
    }
}
