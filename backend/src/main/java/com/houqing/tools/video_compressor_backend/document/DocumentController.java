package com.houqing.tools.video_compressor_backend.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.auth.CurrentUser;
import com.houqing.tools.video_compressor_backend.auth.DownloadTicketService;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.media.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Document Studio API (V2). Conversions run on the shared task engine; inspection and preview are synchronous. */
@RestController
@RequestMapping("/api/v2/documents")
public class DocumentController {
    static final int PREVIEW_DPI = 110;
    static final int PREVIEW_MAX_PX = 1800;

    private final TaskEngine engine;
    private final StorageService storage;
    private final DocumentInspectionService inspection;
    private final DownloadTicketService tickets;
    private final ToolLocator tools;
    private final ObjectMapper mapper;

    public DocumentController(TaskEngine engine, StorageService storage, DocumentInspectionService inspection,
                              DownloadTicketService tickets, ToolLocator tools, ObjectMapper mapper) {
        this.engine = engine;
        this.storage = storage;
        this.inspection = inspection;
        this.tickets = tickets;
        this.tools = tools;
        this.mapper = mapper;
    }

    public record ConvertRequest(@NotBlank String sourceFileId, @NotBlank String targetFormat, JsonNode options) {}

    @PostMapping("/convert")
    public ResponseEntity<Map<String, Object>> convert(@Valid @RequestBody ConvertRequest req,
                                                       @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        ObjectNode params = mapper.createObjectNode();
        params.put("targetFormat", req.targetFormat());
        params.set("options", req.options() == null || req.options().isNull() ? mapper.createObjectNode() : req.options());
        TaskEntity t = engine.submit(CurrentUser.id(), "document.convert", params, List.of(req.sourceFileId()), "PRO", key);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("taskId", t.getId(), "status", t.getStatus(), "statusUrl", "/api/tasks/" + t.getId()));
    }

    /** The routes this installation can really run right now (the UI offers nothing else). */
    @GetMapping("/matrix")
    public ObjectNode matrix() {
        boolean office = tools.available(Tool.SOFFICE);
        boolean browser = tools.available(Tool.CHROMIUM);
        boolean pdfTools = tools.available(Tool.MAGICK) && tools.available(Tool.GHOSTSCRIPT);
        ObjectNode n = mapper.createObjectNode();
        ArrayNode routes = n.putArray("routes");
        route(routes, "docx", "pdf", "LibreOffice", office, "document.convert");
        route(routes, "xlsx", "pdf", "LibreOffice", office, "document.convert");
        route(routes, "html", "pdf", "Chromium（无脚本、无网络）", browser, "document.convert");
        route(routes, "image", "pdf", "ImageMagick", pdfTools, "pdf.process");
        route(routes, "pdf", "png/jpg", "Ghostscript", pdfTools, "pdf.process");
        ArrayNode no = n.putArray("notSupported");
        for (String s : List.of("pdf → docx / xlsx（暂不提供）", "xlsx → docx、docx → xlsx、html → xlsx", "旧版 .doc / .xls、扫描件 OCR")) no.add(s);
        return n;
    }

    private static void route(ArrayNode a, String from, String to, String engine, boolean available, String taskType) {
        ObjectNode r = a.addObject();
        r.put("from", from);
        r.put("to", to);
        r.put("engine", engine);
        r.put("available", available);
        r.put("taskType", taskType);
    }

    @GetMapping("/{fileId}/inspect")
    public ObjectNode inspect(@PathVariable String fileId) {
        return inspection.inspect(storage.require(CurrentUser.id(), fileId));
    }

    /** Page preview of a PDF (an uploaded one or a conversion result) through a short-lived signed image URL. */
    @GetMapping("/{fileId}/preview")
    public Map<String, Object> preview(@PathVariable String fileId, @RequestParam(defaultValue = "1") int page) {
        long uid = CurrentUser.id();
        MediaFile f = storage.require(uid, fileId);
        if (f.kind() != MediaKind.PDF) {
            throw new ApiException(ErrorCode.CAPABILITY_UNSUPPORTED, "仅 PDF 可以按页预览；Word / Excel / HTML 请先转换为 PDF");
        }
        PdfFacts.Info info = PdfFacts.read(storage.pathOf(f));
        if (page < 1 || page > info.pageCount()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "页码超出范围（共 " + info.pageCount() + " 页）");
        }
        DownloadTicketService.Ticket t = tickets.issue(uid, fileId);
        return Map.of("page", page, "pageCount", info.pageCount(), "pageSize", info.pageSizeLabel(),
                "imageTicketUrl", "/api/v2/documents/" + fileId + "/preview/" + page + ".png?ticket=" + t.value(),
                "expiresAt", t.expiresAt());
    }

    /** Ticket-authorised (an &lt;img&gt; cannot send a bearer token); a ticket only opens pages of its own file. */
    @GetMapping(value = "/{fileId}/preview/{page}.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> previewImage(@PathVariable String fileId, @PathVariable int page,
                                               @RequestParam(value = "ticket", required = false) String ticket) {
        long uid = CurrentUser.optionalId()
                .or(() -> tickets.verify(ticket, fileId))
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "预览链接无效或已过期"));
        MediaFile f = storage.require(uid, fileId);
        if (f.kind() != MediaKind.PDF) throw new ApiException(ErrorCode.CAPABILITY_UNSUPPORTED);
        byte[] png = cachedRender(f, page);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(10, TimeUnit.MINUTES).cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.IMAGE_PNG)
                .body(png);
    }

    private byte[] cachedRender(MediaFile f, int page) {
        Path dir = storage.newWorkDir("doc-preview-" + f.getId());
        Path png = dir.resolve(page + ".png");
        try {
            if (Files.isRegularFile(png)) return Files.readAllBytes(png);
            byte[] bytes = PdfFacts.renderPng(storage.pathOf(f), page, PREVIEW_DPI, PREVIEW_MAX_PX);
            Files.write(png, bytes);
            return bytes;
        } catch (java.io.IOException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR);
        }
    }
}
