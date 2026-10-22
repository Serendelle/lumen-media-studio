package com.houqing.tools.video_compressor_backend.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.media.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.*;
import org.jsoup.Jsoup;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Concrete format conversion, using text/table extraction or explicitly labelled page-image layout. */
@Service
public class UniversalConversionService {
    public static final List<String> FORMATS = List.of("docx", "html", "xlsx", "ofd", "pdf", "png", "jpg");
    private static final Set<String> OPTIONS = Set.of("mode", "density", "pageSize", "orientation", "marginMm", "sheetNames");
    private static final int MAX_PAGES = 100;
    private static final long MAX_BYTES = 200L * 1024 * 1024;
    private final StorageService storage;
    private final ObjectMapper mapper;
    private final OfdConversionService ofd;
    private final EditableDocumentService editable;

    public record PreparedPdf(Path file, ObjectNode summary) {}
    @FunctionalInterface public interface PdfProducer { PreparedPdf produce() throws Exception; }
    private record Page(Path file, double widthMm, double heightMm) {}

    public UniversalConversionService(StorageService storage, ObjectMapper mapper, OfdConversionService ofd,
                                      EditableDocumentService editable) {
        this.storage = storage; this.mapper = mapper; this.ofd = ofd; this.editable = editable;
    }

    public static String source(MediaFile f) {
        return DocumentInspectionService.formatOf(f).name().toLowerCase(Locale.ROOT);
    }
    public static String target(JsonNode p) {
        String s = p.path("targetFormat").asText("").toLowerCase(Locale.ROOT);
        return s.equals("jpeg") ? "jpg" : s;
    }
    public static boolean image(String f) { return f.equals("png") || f.equals("jpg"); }
    public static String mode(String source, JsonNode p) {
        return p.path("options").path("mode").asText(
                Set.of("docx", "xlsx", "html").contains(target(p)) && !Set.of("pdf", "ofd", "png", "jpg").contains(source) ? "editable" : "layout");
    }
    public static boolean needsLegacyPdf(String from, String to, String mode) {
        return Set.of("docx", "xlsx", "html").contains(from)
                && (Set.of("pdf", "ofd", "png", "jpg").contains(to) || mode.equals("layout"));
    }

    public void validate(JsonNode p, List<MediaFile> inputs) {
        if (inputs.isEmpty() || inputs.size() > MAX_PAGES) bad("请选择 1-100 个文件；批量合并仅支持 PNG/JPG 图片");
        String from = source(inputs.get(0)), to = target(p), mode = mode(from, p);
        if (!FORMATS.contains(to)) bad("不支持的目标格式：" + to);
        if (inputs.size() == 1 && from.equals(to)) bad("源格式与目标格式相同，请选择其他格式");
        if (inputs.size() > 1 && inputs.stream().anyMatch(f -> !image(source(f)))) bad("多文件转换仅支持 PNG/JPG 图片");
        JsonNode o = p.path("options");
        if (!o.isMissingNode() && !o.isNull() && !o.isObject()) bad("options 必须是对象");
        o.fieldNames().forEachRemaining(k -> { if (!OPTIONS.contains(k)) bad("不支持的转换参数：" + k); });
        if (!Set.of("layout", "editable").contains(mode)) bad("mode 仅支持 layout 或 editable");
        if (!Set.of("docx", "xlsx", "html").contains(to) && !mode.equals("layout")) bad("此目标格式使用页面转换，请选择 layout 模式");
        if (o.has("density") && (!o.path("density").isIntegralNumber() || !Set.of(96, 150, 300).contains(o.path("density").asInt())))
            bad("density 仅支持 96、150 或 300 DPI");
        if (image(from) && mode.equals("editable") && Set.of("docx", "xlsx", "html").contains(to))
            throw new ApiException(ErrorCode.CAPABILITY_UNSUPPORTED, "图片只支持嵌入页面外观；暂不提供 OCR 文字识别");
        if (from.equals("ofd") && mode.equals("editable") && Set.of("docx", "xlsx", "html").contains(to))
            throw new ApiException(ErrorCode.CAPABILITY_UNSUPPORTED, "当前 OFD 引擎以页面图像转换；可选择保留页面外观，暂不提供 OFD 可编辑文本提取");
        if ((from.equals("ofd") || to.equals("ofd")) && !ofd.available())
            throw new ApiException(ErrorCode.CONVERTER_UNAVAILABLE, "OFD 转换引擎未安装，请先构建独立 OFD worker");
        if (o.has("sheetNames") && !from.equals("xlsx")) bad("sheetNames 只适用于 Excel");
        if (!needsLegacyPdf(from, to, mode) && (o.has("sheetNames") || o.has("pageSize") || o.has("orientation") || o.has("marginMm")))
            bad("页面设置与工作表选择仅适用于 Word / Excel / HTML 的页面渲染转换");
        long bytes = inputs.stream().mapToLong(MediaFile::getSizeBytes).sum();
        if (bytes > MAX_BYTES) throw new ApiException(ErrorCode.FILE_TOO_LARGE, "本次转换输入总大小不能超过 200MB");
    }

    public MediaTaskHandler.HandlerResult execute(TaskContext ctx, PdfProducer producer) throws Exception {
        validate(ctx.params(), ctx.inputs());
        String from = source(ctx.inputs().get(0)), to = target(ctx.params()), mode = mode(from, ctx.params());
        Path dir = Files.createDirectories(ctx.workDir().resolve("universal"));
        Path out = dir.resolve("result." + to);
        ObjectNode summary = mapper.createObjectNode();
        summary.put("sourceFormat", from); summary.put("targetFormat", to); summary.put("mode", mode);
        summary.put("ocrPerformed", false); summary.put("engine", "PDFBox / Apache POI / ImageIO");
        var warnings = summary.putArray("warnings");
        long started = System.nanoTime();
        ctx.progress(5, "准备转换");
        Path source = ctx.inputPath(ctx.inputs().get(0));
        if (from.equals("ofd")) {
            source = ofd.toPdf(source, Files.createDirectories(dir.resolve("ofd-pdf")), ctx.token());
            warnings.add("OFD 引擎先以 150 DPI（大页面自动降低）渲染页面图像；更高的输出 DPI 不会增加原图细节，不保留可编辑文本层和数字签名效力");
        }
        boolean textTarget = Set.of("docx", "xlsx", "html").contains(to);
        boolean directEditable = textTarget && mode.equals("editable");
        List<Page> pages = null;
        Path pdf = null;
        if (directEditable) {
            ObjectNode facts = editable.convert(source, from.equals("ofd") ? "pdf" : from, to, out, ctx.token());
            facts.path("warnings").forEach(warnings::add);
            facts.fieldNames().forEachRemaining(k -> { if (!k.equals("warnings") && !k.equals("sourceFormat")) summary.set(k, facts.get(k)); });
            summary.put("engine", "Apache POI / PDFBox 文本与表格提取");
        } else {
            if (image(from)) {
                pages = new ArrayList<>();
                int i = 0; long normalizedBytes = 0;
                for (MediaFile f : ctx.inputs()) {
                    check(ctx, started);
                    Path path = ctx.inputPath(f);
                    int[] size = imageSize(path);
                    // The source has no reliable print size: use 96 DPI, with a maximum 2 m page dimension.
                    double factor = Math.min(25.4 / 96, 2000.0 / Math.max(size[0], size[1]));
                    if (!image(to)) {
                        path = normalizeImage(path, dir.resolve("source-page-" + i + ".png"));
                        normalizedBytes += Files.size(path); embeddingBudget(normalizedBytes);
                    }
                    pages.add(new Page(path, Math.max(1, size[0] * factor), Math.max(1, size[1] * factor)));
                    ctx.progress(10 + (++i * 15 / ctx.inputs().size()), "读取图片");
                }
            } else {
                if (Set.of("pdf", "ofd").contains(from)) pdf = source;
                else {
                    PreparedPdf prepared = producer.produce(); pdf = prepared.file();
                    prepared.summary().path("warnings").forEach(warnings::add);
                    prepared.summary().fieldNames().forEachRemaining(k -> {
                        if (!Set.of("warnings", "sourceFormat", "targetFormat", "verification", "outputSize", "mode").contains(k)) summary.set(k, prepared.summary().get(k));
                    });
                    summary.set("pageSetup", prepared.summary().path("pageSetup"));
                    summary.put("pageEngine", prepared.summary().path("engine").asText());
                }
                if (!to.equals("pdf")) pages = render(pdf, dir, ctx, started);
            }
            check(ctx, started);
            if (to.equals("pdf")) {
                if (pdf == null) writePdf(pages, out, ctx, started);
                else Files.copy(pdf, out);
            } else if (to.equals("ofd")) {
                ofd.fromPages(pages.stream().map(p -> new OfdConversionService.PageImage(p.file(), p.widthMm(), p.heightMm())).toList(), out, ctx.token());
                warnings.add("OFD 使用逐页图像保留外观，文字不可编辑；原有数字签名不随转换保留");
            } else if (image(to)) {
                boolean archive = !image(from) || ctx.inputs().size() > 1;
                if (archive) {
                    out = dir.resolve("result.zip");
                    writeImagesZip(pages, to, out, dir, ctx, started);
                    summary.put("archive", true);
                } else writeImage(pages.get(0).file(), to, out);
            } else {
                if (to.equals("docx")) writeDocx(pages, out, ctx, started);
                else if (to.equals("xlsx")) writeXlsx(pages, out, ctx, started);
                else writeHtml(pages, out, ctx, started);
                warnings.add("此结果以页面图片保留外观，文字和表格不可直接编辑；可编辑文字请另选提取模式（不含 OCR）");
                if (to.equals("docx") && pages.stream().anyMatch(p -> Math.max(p.widthMm(), p.heightMm()) > 550))
                    warnings.add("部分页面超出 Word 页面尺寸范围，已按比例缩小至最长边 550mm");
            }
            summary.put("pages", pages == null ? PdfFacts.read(out).pageCount() : pages.size());
        }
        check(ctx, started);
        if (Files.size(out) > MAX_BYTES) throw new ApiException(ErrorCode.FILE_TOO_LARGE, "转换结果超过 200MB，请减少页数或降低 DPI");
        ctx.progress(90, "核验实际文件");
        boolean archive = out.getFileName().toString().endsWith(".zip");
        int count = verifyOutput(out, to, archive);
        MediaVerifier.Verification v = new MediaVerifier.Verification(mapper);
        v.check("实际格式与内容可重新解析", count > 0, "非空且可解析的 " + to, count + " 个页面/内容单元", ErrorCode.OUTPUT_INVALID);
        v.measured().put("actualBytes", Files.size(out)); v.measured().put("units", count);
        v.throwIfFailed(); summary.set("verification", v.toJson());
        summary.put("outputSize", Files.size(out)); summary.put("verified", true);
        String ext = archive ? "zip" : to;
        MediaKind kind = archive ? MediaKind.ARCHIVE : to.equals("pdf") ? MediaKind.PDF : image(to) ? MediaKind.IMAGE : MediaKind.DOCUMENT;
        String name = StorageService.baseName(ctx.inputs().get(0).getOriginalName()) + "." + ext;
        MediaFile result = storage.registerOutput(ctx.ownerId(), out, name, kind, mime(ext), ctx.taskId());
        return new MediaTaskHandler.HandlerResult(result, summary);
    }

    private List<Page> render(Path pdf, Path dir, TaskContext ctx, long started) throws Exception {
        List<Page> pages = new ArrayList<>();
        try (PDDocument doc = PdfFacts.open(pdf)) {
            if (doc.getNumberOfPages() < 1 || doc.getNumberOfPages() > MAX_PAGES) bad("页面渲染转换支持 1-100 页，请先拆分文档");
            PDFRenderer renderer = new PDFRenderer(doc);
            long bytes = 0;
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                check(ctx, started);
                var box = doc.getPage(i).getCropBox();
                double w = box.getWidth(), h = box.getHeight();
                if (w <= 0 || h <= 0 || !Double.isFinite(w * h) || w > 5669 || h > 5669) bad("PDF 页面尺寸无效或超过 2 米");
                double dpi = ctx.params().path("options").path("density").asInt(150);
                dpi = Math.min(dpi, Math.min(6400 * 72 / Math.max(w, h), Math.sqrt(12_000_000.0 / (w * h)) * 72));
                Path page = dir.resolve("page-" + (i + 1) + ".png");
                BufferedImage bitmap = renderer.renderImageWithDPI(i, (float)dpi, ImageType.RGB);
                try { if (!ImageIO.write(bitmap, "png", page.toFile())) throw new IOException("PNG encoder unavailable"); }
                finally { bitmap.flush(); }
                bytes += Files.size(page);
                if (bytes > MAX_BYTES) throw new ApiException(ErrorCode.FILE_TOO_LARGE, "渲染页面总大小超过 200MB，请降低 DPI");
                int rotation = Math.floorMod(doc.getPage(i).getRotation(), 180);
                pages.add(new Page(page, (rotation == 90 ? h : w) * 25.4 / 72, (rotation == 90 ? w : h) * 25.4 / 72));
                ctx.progress(25 + (i + 1) * 40 / doc.getNumberOfPages(), "渲染第 " + (i + 1) + " 页");
            }
        }
        return pages;
    }

    private static void writePdf(List<Page> pages, Path output, TaskContext ctx, long started) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (Page p : pages) {
                check(ctx, started); imageSize(p.file());
                float w = (float)(p.widthMm() * 72 / 25.4), h = (float)(p.heightMm() * 72 / 25.4);
                PDPage page = new PDPage(new PDRectangle(w, h)); doc.addPage(page);
                PDImageXObject img = PDImageXObject.createFromFileByContent(p.file().toFile(), doc);
                try (PDPageContentStream content = new PDPageContentStream(doc, page)) { content.drawImage(img, 0, 0, w, h); }
            }
            doc.save(output.toFile());
        }
    }
    private static void writeDocx(List<Page> pages, Path output, TaskContext ctx, long started) throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            long embedded = 0;
            for (int i = 0; i < pages.size(); i++) {
                check(ctx, started); Page p = pages.get(i);
                double scale = Math.min(1, 550 / Math.max(p.widthMm(), p.heightMm()));
                double w = Math.max(20, p.widthMm() * scale), h = Math.max(20, p.heightMm() * scale);
                XWPFParagraph paragraph = doc.createParagraph(); paragraph.setSpacingBefore(0); paragraph.setSpacingAfter(0);
                paragraph.setSpacingBetween(1); paragraph.setAlignment(ParagraphAlignment.CENTER);
                byte[] png = pngBytes(p.file());
                embedded += png.length; embeddingBudget(embedded);
                int[] pixels = imageSize(p.file());
                double pictureScale = Math.min(Math.max(1, w - 6) / pixels[0], Math.max(1, h - 8) / pixels[1]);
                try (var in = new ByteArrayInputStream(png)) { paragraph.createRun().addPicture(in, org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG,
                        "page-" + (i + 1) + ".png", Units.toEMU(pixels[0] * pictureScale * 72 / 25.4), Units.toEMU(pixels[1] * pictureScale * 72 / 25.4)); }
                CTSectPr sect = i == pages.size() - 1 ? doc.getDocument().getBody().addNewSectPr() : paragraph.getCTP().getPPr().addNewSectPr();
                var sz = sect.addNewPgSz(); sz.setW(BigInteger.valueOf(Math.round(w / 25.4 * 1440))); sz.setH(BigInteger.valueOf(Math.round(h / 25.4 * 1440)));
                var margin = sect.addNewPgMar(); margin.setTop(BigInteger.valueOf(170)); margin.setBottom(BigInteger.valueOf(170));
                margin.setLeft(BigInteger.valueOf(170)); margin.setRight(BigInteger.valueOf(170));
                if (i < pages.size() - 1) sect.addNewType().setVal(STSectionMark.NEXT_PAGE);
            }
            try (var os = Files.newOutputStream(output)) { doc.write(os); }
        }
    }
    private static void writeXlsx(List<Page> pages, Path output, TaskContext ctx, long started) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            long embedded = 0;
            for (int i = 0; i < pages.size(); i++) {
                check(ctx, started); Page p = pages.get(i); int[] size = imageSize(p.file());
                var sheet = wb.createSheet("Page " + (i + 1));
                sheet.createRow(0).createCell(0).setCellValue("页面图片（保留外观，非可编辑单元格）");
                double factor = Math.min(1, Math.min(1400.0 / size[0], 1800.0 / size[1]));
                int width = Math.max(1, (int)(size[0] * factor)), height = Math.max(1, (int)(size[1] * factor));
                byte[] png = pngBytes(p.file()); embedded += png.length; embeddingBudget(embedded);
                int index = wb.addPicture(png, Workbook.PICTURE_TYPE_PNG);
                var anchor = wb.getCreationHelper().createClientAnchor(); anchor.setCol1(0); anchor.setRow1(1);
                anchor.setCol2(width / 64); anchor.setDx2(Units.pixelToEMU(width % 64));
                anchor.setRow2(1 + height / 20); anchor.setDy2(Units.pixelToEMU(height % 20));
                anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_DONT_RESIZE);
                sheet.createDrawingPatriarch().createPicture(anchor, index);
            }
            try (var os = Files.newOutputStream(output)) { wb.write(os); }
        }
    }
    private static void writeHtml(List<Page> pages, Path output, TaskContext ctx, long started) throws Exception {
        try (var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            long embedded = 0;
            writer.write("<!doctype html><html><head><meta charset=\"UTF-8\"><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src data:; style-src 'unsafe-inline'\"><title>Converted pages</title><style>body{margin:0;background:#eee}figure{margin:20px auto;width:fit-content;page-break-after:always}img{max-width:100%;height:auto}figcaption{text-align:center}</style></head><body>");
            for (int i = 0; i < pages.size(); i++) {
                check(ctx, started);
                byte[] png = pngBytes(pages.get(i).file()); embedded += (png.length + 2L) / 3 * 4; embeddingBudget(embedded);
                writer.write("<figure><img alt=\"Page " + (i + 1) + "\" src=\"data:image/png;base64," + Base64.getEncoder().encodeToString(png) + "\"><figcaption>" + (i + 1) + "</figcaption></figure>");
            }
            writer.write("</body></html>");
        }
    }
    private void writeImagesZip(List<Page> pages, String format, Path output, Path dir, TaskContext ctx, long started) throws Exception {
        ObjectNode manifest = mapper.createObjectNode(); manifest.put("format", format); manifest.put("pageCount", pages.size());
        var entries = manifest.putArray("pages");
        try (var zip = new ZipOutputStream(Files.newOutputStream(output), StandardCharsets.UTF_8)) {
            for (int i = 0; i < pages.size(); i++) {
                check(ctx, started); String name = String.format(Locale.ROOT, "page-%04d.%s", i + 1, format);
                Path converted = dir.resolve("encoded-" + name); writeImage(pages.get(i).file(), format, converted);
                zip.putNextEntry(new ZipEntry(name)); Files.copy(converted, zip); zip.closeEntry();
                var entry = entries.addObject(); entry.put("name", name); entry.put("sizeBytes", Files.size(converted)); entry.put("sha256", sha256(converted));
            }
            zip.putNextEntry(new ZipEntry("manifest.json")); zip.write(mapper.writeValueAsBytes(manifest)); zip.closeEntry();
        }
    }
    private static byte[] pngBytes(Path file) throws IOException {
        imageSize(file);
        if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")) { embeddingBudget(Files.size(file)); return Files.readAllBytes(file); }
        BufferedImage image = ImageIO.read(file.toFile());
        try (var out = new ByteArrayOutputStream()) { if (!ImageIO.write(image, "png", out)) throw new IOException("PNG encoder"); return out.toByteArray(); }
        finally { if (image != null) image.flush(); }
    }
    /** Decode large image inputs with subsampling before embedding them into in-memory Office packages. */
    private static Path normalizeImage(Path source, Path output) throws IOException {
        int[] dimensions = imageSize(source);
        double factor = Math.max(1, Math.max(Math.sqrt((double)dimensions[0] * dimensions[1] / 12_000_000), Math.max(dimensions[0], dimensions[1]) / 6400.0));
        int sample = (int)Math.ceil(factor);
        try (var input = ImageIO.createImageInputStream(source.toFile())) {
            var reader = ImageIO.getImageReaders(input).next();
            try {
                reader.setInput(input); var options = reader.getDefaultReadParam(); options.setSourceSubsampling(sample, sample, 0, 0);
                BufferedImage bitmap = reader.read(0, options);
                if (bitmap == null) throw new IOException("Invalid image");
                try { if (!ImageIO.write(bitmap, "png", output.toFile())) throw new IOException("PNG encoder unavailable"); }
                finally { bitmap.flush(); }
            } finally { reader.dispose(); }
        }
        return output;
    }
    private static void embeddingBudget(long bytes) {
        if (bytes > MAX_BYTES) throw new ApiException(ErrorCode.FILE_TOO_LARGE, "嵌入页面超过 200MB，请减少图片或降低渲染精度");
    }
    private static void writeImage(Path file, String format, Path out) throws IOException {
        imageSize(file); BufferedImage image = ImageIO.read(file.toFile());
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = rgb.createGraphics(); graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
        graphics.drawImage(image, 0, 0, null); graphics.dispose();
        try { if (!ImageIO.write(format.equals("jpg") ? rgb : image, format, out.toFile())) throw new IOException("Image encoder unavailable"); }
        finally { image.flush(); rgb.flush(); }
    }
    public static int[] imageSize(Path path) throws IOException {
        try (var in = ImageIO.createImageInputStream(path.toFile())) {
            if (in == null) throw new IOException("Invalid image");
            var readers = ImageIO.getImageReaders(in); if (!readers.hasNext()) throw new IOException("Image cannot be decoded");
            var reader = readers.next();
            try {
                reader.setInput(in); int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w < 1 || h < 1 || (long)w * h > 40_000_000) throw new ApiException(ErrorCode.FILE_TOO_LARGE, "图片像素数不能超过 4000 万");
                return new int[]{w, h};
            } finally { reader.dispose(); }
        }
    }
    /** Re-open actual bytes independently; also used by the AI quality reviewer. */
    public static int verifyOutput(Path path, String format, boolean archive) throws Exception {
        if (!Files.isRegularFile(path) || Files.size(path) < 1 || Files.size(path) > MAX_BYTES) throw new IOException("Invalid output size");
        if (archive) {
            try (ZipFile zip = new ZipFile(path.toFile())) {
                var entry = zip.getEntry("manifest.json"); if (entry == null || entry.getSize() > 1024 * 1024) throw new IOException("Missing manifest");
                JsonNode manifest; try (var in = zip.getInputStream(entry)) { manifest = new ObjectMapper().readTree(in); }
                int count = manifest.path("pageCount").asInt();
                if (count < 1 || count > MAX_PAGES || manifest.path("pages").size() != count || zip.size() != count + 1 || !format.equals(manifest.path("format").asText())) throw new IOException("Invalid page archive");
                long expanded = 0; Set<String> names = new HashSet<>();
                for (JsonNode p : manifest.path("pages")) {
                    String name = p.path("name").asText();
                    if (!name.matches("page-[0-9]{4}\\." + format) || !names.add(name)) throw new IOException("Unsafe archive entry");
                    var item = zip.getEntry(name); if (item == null || item.getSize() < 1 || item.getSize() > MAX_BYTES) throw new IOException("Invalid page");
                    expanded += item.getSize(); if (expanded > MAX_BYTES) throw new IOException("Page archive too large");
                    byte[] bytes; try (var in = zip.getInputStream(item)) { bytes = in.readNBytes((int)Math.min(MAX_BYTES + 1, item.getSize() + 1)); }
                    if (bytes.length != p.path("sizeBytes").asLong() || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(p.path("sha256").asText())) throw new IOException("Page checksum mismatch");
                    try (var in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) { verifyImage(in, format); }
                }
                return count;
            }
        }
        return switch (format) {
            case "pdf" -> { try (PDDocument doc = PdfFacts.open(path)) {
                int pages = doc.getNumberOfPages(); if (pages < 1) throw new IOException("Empty PDF");
                PDFRenderer renderer = new PDFRenderer(doc);
                for (int index : new LinkedHashSet<>(List.of(0, pages - 1))) {
                    var box = doc.getPage(index).getCropBox(); double w = box.getWidth(), h = box.getHeight();
                    if (w <= 0 || h <= 0 || !Double.isFinite(w * h) || w > 5669 || h > 5669) throw new IOException("Invalid PDF page dimensions");
                    float dpi = (float)Math.min(36, Math.min(2048 * 72 / Math.max(w, h), Math.sqrt(4_000_000 / (w * h)) * 72));
                    renderer.renderImageWithDPI(index, dpi).flush();
                }
                yield pages;
            } }
            case "ofd" -> OfdSafety.validate(path);
            case "docx" -> { try (var in = Files.newInputStream(path); var doc = new XWPFDocument(in)) { int count = doc.getParagraphs().size() + doc.getTables().size() + doc.getAllPictures().size(); if (count < 1) throw new IOException("Empty DOCX"); yield count; } }
            case "xlsx" -> { try (var in = Files.newInputStream(path); var wb = new XSSFWorkbook(in)) { if (wb.getNumberOfSheets() < 1) throw new IOException("Empty XLSX"); yield wb.getNumberOfSheets(); } }
            case "html" -> { var doc = Jsoup.parse(path.toFile(), "UTF-8"); if (doc.body().text().isBlank() && doc.select("img[src^=data:image/]").isEmpty()) throw new IOException("Empty HTML"); yield Math.max(1, doc.select("img").size()); }
            case "png", "jpg" -> { imageSize(path); try (var in = ImageIO.createImageInputStream(path.toFile())) { verifyImage(in, format); } yield 1; }
            default -> throw new IOException("Unsupported output");
        };
    }
    private static void verifyImage(javax.imageio.stream.ImageInputStream in, String format) throws IOException {
        var readers = ImageIO.getImageReaders(in); if (!readers.hasNext()) throw new IOException("Invalid image bytes");
        var reader = readers.next();
        try { reader.setInput(in); String actual = reader.getFormatName().toLowerCase(Locale.ROOT);
            if (!(format.equals("jpg") ? actual.equals("jpeg") || actual.equals("jpg") : actual.equals(format)) || reader.getWidth(0) < 1 || reader.getHeight(0) < 1 || (long)reader.getWidth(0) * reader.getHeight(0) > 40_000_000) throw new IOException("Image format/dimensions mismatch");
            BufferedImage bitmap = reader.read(0); if (bitmap == null) throw new IOException("Image cannot be decoded"); bitmap.flush();
        } finally { reader.dispose(); }
    }
    private static String sha256(Path path) throws Exception { try (var in = Files.newInputStream(path)) { var digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[65536]; for (int n; (n = in.read(buffer)) != -1;) digest.update(buffer, 0, n); return HexFormat.of().formatHex(digest.digest()); } }
    private static void check(TaskContext ctx, long started) {
        ctx.token().throwIfCancelled();
        if (System.nanoTime() - started > 120_000_000_000L) throw new ApiException(ErrorCode.DOCUMENT_CONVERSION_TIMEOUT, "文档转换超过 120 秒，请减少页数或降低 DPI");
    }
    private static void bad(String message) { throw new ApiException(ErrorCode.VALIDATION_FAILED, message); }
    private static String mime(String format) { return switch (format) {
        case "pdf" -> "application/pdf"; case "ofd" -> "application/ofd"; case "png" -> "image/png"; case "jpg" -> "image/jpeg";
        case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; case "html" -> "text/html"; default -> "application/zip";
    }; }
}
