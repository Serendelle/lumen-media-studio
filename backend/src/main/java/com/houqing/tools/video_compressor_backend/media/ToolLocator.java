package com.houqing.tools.video_compressor_backend.media;

import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves external executables: explicit config path → PATH → well-known Windows install locations.
 * Lets a judge's machine work after a plain winget install without editing any config.
 */
@Component
public class ToolLocator {
    private static final Logger log = LoggerFactory.getLogger(ToolLocator.class);
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private final AppProperties props;
    private final Map<Tool, Optional<List<String>>> cache = new ConcurrentHashMap<>();

    public ToolLocator(AppProperties props) { this.props = props; }

    /** Command prefix to invoke the tool (rembg via conda may be several tokens). */
    public List<String> command(Tool tool) {
        return find(tool).orElseThrow(() -> new ApiException(ErrorCode.TOOL_UNAVAILABLE,
                tool.label() + " 未安装或未找到，请参考部署文档安装"));
    }

    public boolean available(Tool tool) { return find(tool).isPresent(); }

    public Optional<List<String>> find(Tool tool) {
        return cache.computeIfAbsent(tool, this::resolve);
    }

    public void refresh() { cache.clear(); }

    private Optional<List<String>> resolve(Tool tool) {
        AppProperties.Tools t = props.tools();
        Optional<List<String>> r = switch (tool) {
            case FFMPEG -> single(t.ffmpeg(), "ffmpeg", wingetFfmpeg("ffmpeg"));
            case FFPROBE -> single(t.ffprobe(), "ffprobe", wingetFfmpeg("ffprobe"));
            case EXIFTOOL -> single(t.exiftool(), "exiftool",
                    List.of(localAppData().resolve("Programs/ExifTool/ExifTool.exe")));
            case MAGICK -> single(t.magick(), "magick", globExe(Path.of("C:/Program Files"), "ImageMagick-*", "magick.exe"));
            case GHOSTSCRIPT -> single(t.ghostscript(), WINDOWS ? "gswin64c" : "gs", ghostscriptCandidates());
            case REMBG -> rembg(t);
            // soffice.com is the console launcher: it waits for the conversion and returns its exit code
            case SOFFICE -> single(t.soffice(), WINDOWS ? "soffice.com" : "soffice", officeCandidates());
            case CHROMIUM -> single(t.chromium(), WINDOWS ? "chrome" : "chromium", chromiumCandidates());
            // a dedicated environment, never the system "python" on PATH (which may not have RapidOCR)
            case OCR -> ocrPython(t.ocrPython());
        };
        log.info("Tool {} -> {}", tool.label(), r.map(c -> String.join(" ", c)).orElse("NOT FOUND"));
        return r;
    }

    private Optional<List<String>> single(String configured, String bareName, List<Path> fallbacks) {
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured);
            if (p.isAbsolute() || configured.contains("/") || configured.contains("\\")) {
                return Files.isRegularFile(p) ? Optional.of(List.of(p.toString())) : Optional.empty();
            }
            bareName = configured;
        }
        Optional<Path> onPath = searchPath(bareName);
        if (onPath.isPresent()) return Optional.of(List.of(onPath.get().toString()));
        return fallbacks.stream().filter(Files::isRegularFile).findFirst().map(p -> List.of(p.toString()));
    }

    private Optional<List<String>> rembg(AppProperties.Tools t) {
        if (t.rembg() != null && !t.rembg().isBlank()) {
            return single(t.rembg(), "rembg", List.of());
        }
        String env = t.rembgEnv() == null || t.rembgEnv().isBlank() ? "rembg-env" : t.rembgEnv();
        for (Path condaRoot : condaRoots(t.conda())) {
            Path exe = condaRoot.resolve("envs").resolve(env).resolve(WINDOWS ? "Scripts/rembg.exe" : "bin/rembg");
            if (Files.isRegularFile(exe)) return Optional.of(List.of(exe.toString()));
        }
        return searchPath("rembg").map(p -> List.of(p.toString()));
    }

    private List<Path> condaRoots(String condaPath) {
        List<Path> roots = new ArrayList<>();
        if (condaPath != null && !condaPath.isBlank()) {
            // e.g. D:/anaconda/condabin/conda.bat -> D:/anaconda
            Path p = Path.of(condaPath).toAbsolutePath();
            if (p.getParent() != null && p.getParent().getParent() != null) roots.add(p.getParent().getParent());
        }
        String home = System.getProperty("user.home");
        for (String n : new String[]{"miniconda3", "anaconda3", "Miniconda3", "Anaconda3"}) {
            roots.add(Path.of(home, n));
            roots.add(localAppData().resolve(n));
        }
        roots.add(Path.of("C:/ProgramData/miniconda3"));
        roots.add(Path.of("C:/ProgramData/anaconda3"));
        return roots;
    }

    private static Optional<Path> searchPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) return Optional.empty();
        List<String> exts = WINDOWS ? List.of(".exe", ".bat", ".cmd", "") : List.of("");
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            for (String ext : exts) {
                try {
                    Path p = Path.of(dir.trim(), name + ext);
                    if (Files.isRegularFile(p) && (WINDOWS || Files.isExecutable(p))) return Optional.of(p);
                } catch (Exception ignored) {
                    // malformed PATH entry
                }
            }
        }
        return Optional.empty();
    }

    private static Path localAppData() {
        String v = System.getenv("LOCALAPPDATA");
        return v != null ? Path.of(v) : Path.of(System.getProperty("user.home"), "AppData", "Local");
    }

    private static List<Path> wingetFfmpeg(String exe) {
        Path pkgs = localAppData().resolve("Microsoft/WinGet/Packages");
        List<Path> out = new ArrayList<>(globExe(pkgs, "Gyan.FFmpeg*", null).stream()
                .flatMap(dir -> globExe(dir, "ffmpeg-*", "bin/" + exe + ".exe").stream()).toList());
        out.add(localAppData().resolve("Microsoft/WinGet/Links/" + exe + ".exe"));
        return out;
    }

    private static Optional<List<String>> ocrPython(String configured) {
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured);
            return Files.isRegularFile(p) ? Optional.of(List.of(p.toString())) : Optional.empty();
        }
        String exe = WINDOWS ? "Scripts/python.exe" : "bin/python";
        for (Path root : List.of(Path.of("D:/LumenTools/ocr-venv"), Path.of("C:/LumenTools/ocr-venv"),
                Path.of(System.getProperty("user.home"), ".lumen", "ocr-venv"))) {
            if (Files.isRegularFile(root.resolve(exe))) return Optional.of(List.of(root.resolve(exe).toString()));
        }
        return Optional.empty();
    }

    private static List<Path> officeCandidates() {
        List<Path> out = new ArrayList<>();
        for (String root : List.of("C:/Program Files/LibreOffice", "D:/LibreOffice", "D:/Program Files/LibreOffice",
                "C:/Program Files (x86)/LibreOffice")) {
            out.add(Path.of(root, "program", WINDOWS ? "soffice.com" : "soffice"));
        }
        out.add(Path.of("/usr/bin/soffice"));
        out.add(Path.of("/Applications/LibreOffice.app/Contents/MacOS/soffice"));
        return out;
    }

    /** Chrome first, then Edge (present on every Windows 10/11), so HTML -> PDF needs no extra download. */
    private static List<Path> chromiumCandidates() {
        return List.of(
                Path.of("C:/Program Files/Google/Chrome/Application/chrome.exe"),
                localAppData().resolve("Google/Chrome/Application/chrome.exe"),
                Path.of("C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"),
                Path.of("C:/Program Files/Microsoft/Edge/Application/msedge.exe"),
                Path.of("/usr/bin/chromium"), Path.of("/usr/bin/chromium-browser"), Path.of("/usr/bin/google-chrome"));
    }

    private List<Path> ghostscriptCandidates() {
        List<Path> out = new ArrayList<>();
        String configured = props.tools().ghostscript();
        if (configured != null && !configured.isBlank()) out.add(Path.of(configured));
        for (Path base : List.of(Path.of("C:/Program Files/gs"), Path.of("C:/Program Files (x86)/gs"))) {
            out.addAll(globExe(base, "gs*", "bin/gswin64c.exe"));
        }
        if (WINDOWS) out.addAll(ghostscriptFromRegistry());
        return out;
    }

    /** The Ghostscript installer records GS_DLL (...\bin\gsdll64.dll) in the registry, wherever it was installed. */
    private static List<Path> ghostscriptFromRegistry() {
        List<Path> out = new ArrayList<>();
        try {
            Process p = new ProcessBuilder("reg", "query", "HKLM\\SOFTWARE\\GPL Ghostscript", "/s", "/v", "GS_DLL")
                    .redirectErrorStream(true).start();
            p.getOutputStream().close();
            String text = new String(p.getInputStream().readAllBytes(), java.nio.charset.Charset.defaultCharset());
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            for (String line : text.split("\\R")) {
                int i = line.indexOf("REG_SZ");
                if (line.contains("GS_DLL") && i > 0) {
                    Path dll = Path.of(line.substring(i + "REG_SZ".length()).trim());
                    if (dll.getParent() != null) out.add(dll.getParent().resolve("gswin64c.exe"));
                }
            }
        } catch (Exception ignored) {
            // registry not readable: rely on PATH / config
        }
        out.sort(Comparator.reverseOrder());
        return out;
    }

    /** Matches dir/pattern/suffix; suffix null returns the matched directories themselves. */
    private static List<Path> globExe(Path dir, String pattern, String suffix) {
        if (!Files.isDirectory(dir)) return List.of();
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, pattern)) {
            for (Path p : ds) out.add(suffix == null ? p : p.resolve(suffix));
        } catch (IOException ignored) {
            // unreadable directory
        }
        out.sort(Comparator.reverseOrder()); // newest version first
        return out;
    }
}
