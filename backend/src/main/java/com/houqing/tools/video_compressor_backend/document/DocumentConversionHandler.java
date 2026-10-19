package com.houqing.tools.video_compressor_backend.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.media.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * document.convert — the explicit P0 routes only: DOCX → PDF and XLSX → PDF (LibreOffice headless) and HTML → PDF
 * (Chromium headless). Each run gets its own LibreOffice / browser profile in the task's work dir, a hard 120 s
 * limit, and the user's file is only ever read (inputs are copied first). The PDF is then checked independently:
 * header, page count, and first/last page actually rendering.
 *
 * Params: {targetFormat: "pdf", options: {pageSize: A4|ORIGINAL, orientation: PORTRAIT|LANDSCAPE, marginMm: 0..30,
 * sheetNames: [..]}} — page options apply to HTML; Office files keep their own page setup; sheetNames is XLSX-only.
 */
@Component
public class DocumentConversionHandler implements MediaTaskHandler {
    static final Duration TIMEOUT = Duration.ofSeconds(120);
    static final Set<String> OPTION_KEYS = Set.of("pageSize", "orientation", "marginMm", "sheetNames");

    private final ToolLocator tools;
    private final ProcessRunner runner;
    private final StorageService storage;
    private final DocumentInspectionService inspection;
    private final ObjectMapper mapper;

    public DocumentConversionHandler(ToolLocator tools, ProcessRunner runner, StorageService storage,
                                     DocumentInspectionService inspection, ObjectMapper mapper) {
        this.tools = tools;
        this.runner = runner;
        this.storage = storage;
        this.inspection = inspection;
        this.mapper = mapper;
    }

    @Override public String type() { return "document.convert"; }

    // ---------------- validation (runs before the task is queued) ----------------

    @Override
    public void validate(JsonNode p, List<MediaFile> inputs) {
        if (inputs.size() != 1) throw new ApiException(ErrorCode.VALIDATION_FAILED, "需要且仅需要一个文档");
        MediaFile in = inputs.get(0);
        if (in.kind() == MediaKind.PDF) {
            throw new ApiException(ErrorCode.CAPABILITY_UNSUPPORTED, "PDF 已是目标格式；PDF 转图片请使用「PDF 转换」工具");
        }
        DocumentInspectionService.Format fmt = DocumentInspectionService.formatOf(in);
        String target = p.path("targetFormat").asText("");
        if (!target.equals("pdf")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "不支持的转换方向：" + fmt.name().toLowerCase(Locale.ROOT)
                    + " → " + target + "（当前只支持转换为 PDF）");
        }
        JsonNode o = p.path("options");
        if (!o.isMissingNode() && !o.isNull() && !o.isObject()) throw new ApiException(ErrorCode.VALIDATION_FAILED, "options 必须是对象");
        o.fieldNames().forEachRemaining(k -> {
            if (!OPTION_KEYS.contains(k)) throw new ApiException(ErrorCode.VALIDATION_FAILED, "不支持的转换参数：" + k);
        });
        switch (fmt) {
            case DOCX, XLSX -> {
                if (!tools.available(Tool.SOFFICE)) {
                    throw new ApiException(ErrorCode.CONVERTER_UNAVAILABLE, "未安装 LibreOffice，无法转换 Word / Excel 文档");
                }
                if (o.has("orientation") || o.has("marginMm") || (o.has("pageSize") && !"ORIGINAL".equals(o.path("pageSize").asText()))) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED,
                            "Word / Excel 转 PDF 沿用文档自身的页面设置；页面尺寸、方向与边距只适用于 HTML");
                }
                if (o.has("sheetNames")) {
                    if (fmt != DocumentInspectionService.Format.XLSX) throw new ApiException(ErrorCode.VALIDATION_FAILED, "sheetNames 只适用于 Excel");
                    checkSheets(in, o.path("sheetNames"));
                }
            }
            case HTML -> {
                if (!tools.available(Tool.CHROMIUM)) {
                    throw new ApiException(ErrorCode.CONVERTER_UNAVAILABLE, "未找到 Chrome / Edge，无法转换 HTML");
                }
                if (o.has("sheetNames")) throw new ApiException(ErrorCode.VALIDATION_FAILED, "sheetNames 只适用于 Excel");
                String size = o.path("pageSize").asText("A4");
                if (!size.equals("A4") && !size.equals("ORIGINAL")) throw new ApiException(ErrorCode.VALIDATION_FAILED, "pageSize 仅支持 A4 或 ORIGINAL");
                String orient = o.path("orientation").asText("PORTRAIT");
                if (!orient.equals("PORTRAIT") && !orient.equals("LANDSCAPE")) throw new ApiException(ErrorCode.VALIDATION_FAILED, "orientation 仅支持 PORTRAIT 或 LANDSCAPE");
                if (o.has("marginMm") && (!o.path("marginMm").canConvertToInt() || o.path("marginMm").asInt() < 0 || o.path("marginMm").asInt() > 30)) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "marginMm 需为 0-30 的整数");
                }
            }
            default -> throw new ApiException(ErrorCode.UNSUPPORTED_DOCUMENT);
        }
    }

    private void checkSheets(MediaFile in, JsonNode names) {
        if (!names.isArray() || names.isEmpty() || names.size() > 50) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "sheetNames 需为 1-50 个工作表名称");
        }
        Set<String> existing = new HashSet<>();
        try (InputStream is = Files.newInputStream(storage.pathOf(in)); XSSFWorkbook wb = new XSSFWorkbook(is)) {
            for (int i = 0; i < wb.getNumberOfSheets(); i++) existing.add(wb.getSheetName(i));
        } catch (Exception e) {
            throw new ApiException(ErrorCode.DOCUMENT_CORRUPTED, "Excel 文档无法解析");
        }
        for (JsonNode n : names) {
            if (!existing.contains(n.asText())) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "工作表不存在：" + n.asText(),
                        Map.of("fields", Map.of("sheetNames", "可选：" + String.join("、", existing))));
            }
        }
    }

    // ---------------- execution ----------------

    @Override
    public HandlerResult execute(TaskContext ctx) throws Exception {
        MediaFile in = ctx.inputs().get(0);
        DocumentInspectionService.Format fmt = DocumentInspectionService.formatOf(in);
        JsonNode o = ctx.params().path("options");
        Path inDir = Files.createDirectories(ctx.workDir().resolve("in"));
        Path outDir = Files.createDirectories(ctx.workDir().resolve("out"));
        ObjectNode summary = mapper.createObjectNode();
        summary.put("sourceFormat", fmt.name().toLowerCase(Locale.ROOT));
        summary.put("targetFormat", "pdf");
        summary.put("originalSize", in.getSizeBytes());
        ArrayNode warnings = summary.putArray("warnings");
        Path pdf;

        ctx.progress(5, "preparing");
        if (fmt == DocumentInspectionService.Format.HTML) {
            summary.put("engine", "Chromium（无脚本、无网络）");
            pdf = html(ctx, in, o, inDir, outDir, summary, warnings);
        } else {
            summary.put("engine", "LibreOffice");
            inspection.inspect(in).path("warnings").forEach(warnings::add); // fonts, print areas, ...
            Path source = inDir.resolve("source." + (fmt == DocumentInspectionService.Format.DOCX ? "docx" : "xlsx"));
            if (fmt == DocumentInspectionService.Format.XLSX && o.has("sheetNames")) {
                Set<String> keep = new LinkedHashSet<>();
                o.path("sheetNames").forEach(n -> keep.add(n.asText()));
                selectSheets(ctx.inputPath(in), source, keep);
                summary.set("sheets", o.path("sheetNames"));
            } else {
                Files.copy(ctx.inputPath(in), source); // never hand the stored original to an external program
            }
            pdf = office(ctx, source, outDir);
        }

        ctx.progress(85, "verifying");
        MediaVerifier.Verification v = verify(pdf, summary, warnings);
        summary.set("verification", v.toJson());
        v.throwIfFailed();

        String name = StorageService.baseName(in.getOriginalName()) + ".pdf";
        MediaFile out = storage.registerOutput(ctx.ownerId(), pdf, name, MediaKind.PDF, "application/pdf", ctx.taskId());
        return new HandlerResult(out, summary);
    }

    /** Hides every sheet not chosen (LibreOffice exports only visible sheets) on a copy; the original is untouched. */
    static void selectSheets(Path original, Path copy, Set<String> keep) throws Exception {
        try (InputStream is = Files.newInputStream(original); XSSFWorkbook wb = new XSSFWorkbook(is)) {
            int firstKept = -1;
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                boolean k = keep.contains(wb.getSheetName(i));
                if (k && firstKept < 0) firstKept = i;
            }
            // a workbook must always have a visible active sheet: activate a kept one before hiding the rest
            wb.setActiveSheet(firstKept);
            wb.setFirstVisibleTab(firstKept);
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                wb.getSheetAt(i).setSelected(i == firstKept);
                wb.setSheetHidden(i, !keep.contains(wb.getSheetName(i)));
            }
            try (OutputStream os = Files.newOutputStream(copy)) {
                wb.write(os);
            }
        }
    }

    private Path office(TaskContext ctx, Path source, Path outDir) {
        List<String> cmd = new ArrayList<>(tools.command(Tool.SOFFICE));
        // a private profile per task: no shared state between conversions, and parallel runs do not collide
        cmd.add("-env:UserInstallation=" + ctx.workDir().resolve("lo-profile").toUri());
        cmd.addAll(List.of("--headless", "--invisible", "--norestore", "--nolockcheck", "--nodefault", "--nologo",
                "--convert-to", "pdf", "--outdir", outDir.toString(), source.toString()));
        ctx.progress(15, "converting");
        run(ctx, cmd);
        Path pdf = outDir.resolve(StorageService.baseName(source.getFileName().toString()) + ".pdf");
        if (!Files.isRegularFile(pdf)) throw new ApiException(ErrorCode.DOCUMENT_CONVERSION_FAILED, "LibreOffice 未生成 PDF");
        return pdf;
    }

    private Path html(TaskContext ctx, MediaFile in, JsonNode o, Path inDir, Path outDir, ObjectNode summary, ArrayNode warnings)
            throws Exception {
        String size = o.path("pageSize").asText("A4");
        boolean landscape = "LANDSCAPE".equals(o.path("orientation").asText("PORTRAIT"));
        int margin = o.path("marginMm").asInt(10);
        String pageCss = "@page { " + (size.equals("A4") ? "size: A4 " + (landscape ? "landscape" : "portrait") + "; " : "")
                + "margin: " + margin + "mm }";
        HtmlSanitizer.Result r = HtmlSanitizer.sanitize(Files.readString(ctx.inputPath(in), StandardCharsets.UTF_8), pageCss);
        ObjectNode rem = summary.putObject("removed");
        r.removed().forEach(rem::put);
        if (r.totalRemoved() > 0) warnings.add("已移除 " + r.totalRemoved() + " 处脚本或外部引用，只保留静态内容");
        Path page = inDir.resolve("page.html");
        Files.writeString(page, r.html(), StandardCharsets.UTF_8);
        Path pdf = outDir.resolve("page.pdf");

        List<String> cmd = new ArrayList<>(tools.command(Tool.CHROMIUM));
        cmd.addAll(List.of(
                "--headless=new", "--disable-gpu", "--no-first-run", "--no-default-browser-check",
                "--disable-extensions", "--disable-background-networking", "--disable-sync", "--disable-component-update",
                "--disable-default-apps", "--mute-audio", "--hide-scrollbars",
                // No network: every host name resolves nowhere and every request goes to a dead proxy.
                // Scripts cannot run: the sanitizer strips them and the injected CSP (default-src 'none') blocks any
                // that slipped through. (--blink-settings=scriptEnabled=false would be a third fence, but it silently
                // breaks headless printing, so it is deliberately not used.)
                "--host-resolver-rules=MAP * ~NOTFOUND",
                "--proxy-server=http://127.0.0.1:9", "--proxy-bypass-list=<-loopback>",
                "--user-data-dir=" + ctx.workDir().resolve("browser-profile"),
                "--no-pdf-header-footer",
                "--print-to-pdf=" + pdf,
                page.toUri().toString()));
        ctx.progress(20, "converting");
        run(ctx, cmd);
        if (!Files.isRegularFile(pdf)) throw new ApiException(ErrorCode.DOCUMENT_CONVERSION_FAILED, "浏览器未生成 PDF");
        summary.put("pageSetup", size.equals("A4") ? "A4 " + (landscape ? "横向" : "纵向") + "，边距 " + margin + " mm" : "沿用页面自身设置，边距 " + margin + " mm");
        return pdf;
    }

    private void run(TaskContext ctx, List<String> cmd) {
        ProcessRunner.Result r;
        try {
            r = runner.run(cmd, TIMEOUT, ctx.token(), null, ctx.workDir());
        } catch (ApiException e) {
            if (e.getCode() == ErrorCode.PROCESS_TIMEOUT) {
                throw new ApiException(ErrorCode.DOCUMENT_CONVERSION_TIMEOUT, "文档转换超过 " + TIMEOUT.toSeconds() + " 秒，已中止并清理临时文件");
            }
            if (e.getCode() == ErrorCode.TOOL_UNAVAILABLE) throw new ApiException(ErrorCode.CONVERTER_UNAVAILABLE);
            throw e;
        }
        if (!r.ok()) {
            org.slf4j.LoggerFactory.getLogger(DocumentConversionHandler.class)
                    .warn("converter exited with {}; tail:\n{}", r.exitCode(), r.tail());
            throw new ApiException(ErrorCode.DOCUMENT_CONVERSION_FAILED, "转换引擎返回错误（退出码 " + r.exitCode() + "）");
        }
    }

    /** Independent PDF checks: header, size, ≥1 page, first and last page render. Blank pages are warnings. */
    private MediaVerifier.Verification verify(Path pdf, ObjectNode summary, ArrayNode warnings) throws Exception {
        MediaVerifier.Verification v = new MediaVerifier.Verification(mapper);
        long size = Files.size(pdf);
        v.measured().put("actualBytes", size);
        v.check("PDF 文件头", PdfFacts.hasPdfMagic(pdf), "%PDF-", PdfFacts.hasPdfMagic(pdf) ? "%PDF-" : "不是 PDF", ErrorCode.DOCUMENT_CONVERSION_FAILED);
        v.check("非空", size > 0, "> 0 字节", size + " 字节", ErrorCode.DOCUMENT_CONVERSION_FAILED);
        if (!v.passed()) return v;
        PdfFacts.Info info;
        try {
            info = PdfFacts.read(pdf);
        } catch (ApiException e) {
            v.check("可解析", false, "PDFBox 可打开", e.getMessage(), ErrorCode.DOCUMENT_CONVERSION_FAILED);
            return v;
        }
        v.measured().put("pageCount", info.pageCount());
        v.measured().put("pageSize", info.pageSizeLabel());
        summary.put("pages", info.pageCount());
        summary.put("pageSize", info.pageSizeLabel());
        summary.put("outputSize", size);
        v.check("页数", info.pageCount() >= 1, "≥ 1", String.valueOf(info.pageCount()), ErrorCode.DOCUMENT_CONVERSION_FAILED);
        if (info.pageCount() < 1) return v;
        Set<Integer> sample = new LinkedHashSet<>(List.of(1, info.pageCount()));
        for (int page : sample) {
            String label = page == 1 ? "首页可渲染" : "末页可渲染";
            try {
                boolean ink = PdfFacts.renderHasInk(pdf, page);
                v.check(label, true, "可渲染", ink ? "第 " + page + " 页正常" : "第 " + page + " 页为空白", null);
                if (!ink) warnings.add("第 " + page + " 页渲染为空白，请检查原文档该页是否有内容");
            } catch (ApiException e) {
                v.check(label, false, "可渲染", "第 " + page + " 页无法渲染", ErrorCode.DOCUMENT_CONVERSION_FAILED);
            }
        }
        return v;
    }
}
