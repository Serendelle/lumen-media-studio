package com.houqing.tools.video_compressor_backend.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.media.MediaFile;
import com.houqing.tools.video_compressor_backend.media.MediaKind;
import com.houqing.tools.video_compressor_backend.media.StorageService;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.springframework.stereotype.Service;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Read-only structural facts about a document (no model involved): what a template contains, how it is paged and
 * which fonts it needs. The same facts feed the conversion report and, later, template field mapping.
 */
@Service
public class DocumentInspectionService {
    static final int MAX_SCAN_ROWS = 20_000;

    private final StorageService storage;
    private final ObjectMapper mapper;
    private volatile Set<String> installedFonts;

    public DocumentInspectionService(StorageService storage, ObjectMapper mapper) {
        this.storage = storage;
        this.mapper = mapper;
    }

    public enum Format { DOCX, XLSX, HTML, PDF }

    public static Format formatOf(MediaFile f) {
        String ext = StorageService.extensionOf(f.getStoredName());
        if (f.kind() == MediaKind.PDF) return Format.PDF;
        if (f.kind() != MediaKind.DOCUMENT) throw new ApiException(ErrorCode.UNSUPPORTED_DOCUMENT, "该文件不是文档");
        return switch (ext) {
            case "docx" -> Format.DOCX;
            case "xlsx" -> Format.XLSX;
            case "html" -> Format.HTML;
            default -> throw new ApiException(ErrorCode.UNSUPPORTED_DOCUMENT);
        };
    }

    public ObjectNode inspect(MediaFile f) {
        Format fmt = formatOf(f);
        Path path = storage.pathOf(f);
        ObjectNode n = mapper.createObjectNode();
        n.put("fileId", f.getId());
        n.put("filename", f.getOriginalName());
        n.put("kind", f.getMediaType());
        n.put("format", fmt.name().toLowerCase(Locale.ROOT));
        n.put("sizeBytes", f.getSizeBytes());
        ArrayNode warnings = n.putArray("warnings");
        switch (fmt) {
            case DOCX -> docx(path, n, warnings);
            case XLSX -> xlsx(path, n, warnings);
            case HTML -> html(path, n, warnings);
            case PDF -> pdf(path, n, warnings);
        }
        return n;
    }

    // ---------------- DOCX ----------------

    private void docx(Path path, ObjectNode n, ArrayNode warnings) {
        try (InputStream in = Files.newInputStream(path); XWPFDocument doc = new XWPFDocument(in)) {
            n.put("paragraphs", doc.getParagraphs().size());
            ArrayNode tables = n.putArray("tables");
            int ti = 0;
            for (XWPFTable t : doc.getTables()) {
                int cols = 0;
                for (XWPFTableRow r : t.getRows()) cols = Math.max(cols, r.getTableCells().size());
                ObjectNode tn = tables.addObject();
                tn.put("index", ti++);
                tn.put("rows", t.getNumberOfRows());
                tn.put("cols", cols);
            }
            CTSectPr sect = doc.getDocument().getBody().getSectPr();
            if (sect != null && sect.getPgSz() != null) {
                CTPageSz sz = sect.getPgSz();
                double w = twipsToMm(sz.getW()), h = twipsToMm(sz.getH());
                n.put("pageWidthMm", w);
                n.put("pageHeightMm", h);
                n.put("pageSize", PdfFacts.sizeLabel(w / 25.4 * 72, h / 25.4 * 72));
            }
            Set<String> fonts = new TreeSet<>();
            try {
                // document-wide default fonts (docDefaults/rPrDefault/rPr/rFonts) apply to every run that sets none
                var rpr = doc.getStyle().getDocDefaults().getRPrDefault().getRPr();
                for (var rf : rpr.getRFontsList()) {
                    for (String f : new String[]{rf.getAscii(), rf.getEastAsia()}) {
                        if (f != null && !f.isBlank()) fonts.add(f);
                    }
                }
            } catch (Exception ignored) {
                // documents without a styles part or without defaults
            }
            for (IBodyElement el : doc.getBodyElements()) collectRunFonts(el, fonts);
            fonts(n, fonts, warnings);
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException ae) throw ae;
            throw new ApiException(ErrorCode.DOCUMENT_CORRUPTED, "Word 文档无法解析");
        }
    }

    private static void collectRunFonts(IBodyElement el, Set<String> fonts) {
        if (el instanceof XWPFParagraph p) {
            for (XWPFRun r : p.getRuns()) {
                for (XWPFRun.FontCharRange range : List.of(XWPFRun.FontCharRange.ascii, XWPFRun.FontCharRange.eastAsia)) {
                    String f = r.getFontFamily(range);
                    if (f != null && !f.isBlank() && !f.startsWith("+")) fonts.add(f);
                }
            }
        } else if (el instanceof XWPFTable t) {
            for (XWPFTableRow row : t.getRows()) {
                for (XWPFTableCell c : row.getTableCells()) {
                    for (IBodyElement inner : c.getBodyElements()) collectRunFonts(inner, fonts);
                }
            }
        }
    }

    // ---------------- XLSX ----------------

    private void xlsx(Path path, ObjectNode n, ArrayNode warnings) {
        try (InputStream in = Files.newInputStream(path); XSSFWorkbook wb = new XSSFWorkbook(in)) {
            ArrayNode sheets = n.putArray("sheets");
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Sheet s = wb.getSheetAt(i);
                ObjectNode sn = sheets.addObject();
                sn.put("index", i);
                sn.put("name", s.getSheetName());
                boolean hidden = wb.isSheetHidden(i) || wb.isSheetVeryHidden(i);
                sn.put("hidden", hidden);
                int rows = 0, cols = 0, scanned = 0;
                for (Row r : s) {
                    if (++scanned > MAX_SCAN_ROWS) break;
                    if (r.getLastCellNum() > 0) {
                        rows = Math.max(rows, r.getRowNum() + 1);
                        cols = Math.max(cols, r.getLastCellNum());
                    }
                }
                sn.put("usedRows", rows);
                sn.put("usedCols", cols);
                sn.put("mergedRegions", s.getNumMergedRegions());
                String area = wb.getPrintArea(i);
                if (area != null) sn.put("printArea", area);
                sn.put("landscape", s.getPrintSetup().getLandscape());
                if (!hidden && area == null && rows > 0) {
                    warnings.add("工作表「" + s.getSheetName() + "」未设置打印区域：将按已用区域分页输出，较宽的表格会分到多页（不会裁掉非空列）");
                }
            }
            Set<String> fonts = new TreeSet<>();
            for (int i = 0; i < wb.getNumCellStyles(); i++) {
                CellStyle st = wb.getCellStyleAt(i);
                String name = wb.getFontAt(st.getFontIndex()).getFontName();
                if (name != null && !name.isBlank()) fonts.add(name);
            }
            fonts(n, fonts, warnings);
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException ae) throw ae;
            throw new ApiException(ErrorCode.DOCUMENT_CORRUPTED, "Excel 文档无法解析");
        }
    }

    // ---------------- HTML / PDF ----------------

    private void html(Path path, ObjectNode n, ArrayNode warnings) {
        try {
            HtmlSanitizer.Result r = HtmlSanitizer.sanitize(Files.readString(path, StandardCharsets.UTF_8), null);
            n.put("title", r.title());
            ObjectNode rem = n.putObject("willRemove");
            r.removed().forEach(rem::put);
            if (r.totalRemoved() > 0) {
                warnings.add("转换时会移除 " + r.totalRemoved() + " 处脚本或外部引用（只保留静态内容与内嵌 data: 图片）");
            }
        } catch (IOException e) {
            throw new ApiException(ErrorCode.DOCUMENT_CORRUPTED);
        }
    }

    private void pdf(Path path, ObjectNode n, ArrayNode warnings) {
        PdfFacts.Info info = PdfFacts.read(path);
        n.put("pageCount", info.pageCount());
        n.put("pageSize", info.pageSizeLabel());
        n.put("pageWidthMm", info.firstWidthMm());
        n.put("pageHeightMm", info.firstHeightMm());
        ArrayNode fonts = n.putArray("fonts");
        info.fonts().forEach(fonts::add);
        if (info.ownerRestricted()) warnings.add("PDF 设置了权限限制（无需密码即可打开）");
        if (info.pageCount() == 0) warnings.add("PDF 没有任何页面");
    }

    // ---------------- fonts ----------------

    /** Lists the fonts and names the ones this machine does not have — the converter would substitute those. */
    private void fonts(ObjectNode n, Set<String> fonts, ArrayNode warnings) {
        ArrayNode all = n.putArray("fonts");
        ArrayNode missing = n.putArray("missingFonts");
        for (String f : fonts) {
            all.add(f);
            if (!fontInstalled(f)) missing.add(f);
        }
        if (!missing.isEmpty()) {
            List<String> names = new ArrayList<>();
            missing.forEach(m -> names.add(m.asText()));
            warnings.add("本机未安装字体：" + String.join("、", names) + "。转换时会用替代字体，版式可能与原文档不同");
        }
    }

    public boolean fontInstalled(String family) {
        Set<String> installed = installedFonts;
        if (installed == null) {
            installed = new HashSet<>();
            try {
                GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
                for (Locale l : List.of(Locale.ROOT, Locale.SIMPLIFIED_CHINESE, Locale.US)) {
                    for (String f : ge.getAvailableFontFamilyNames(l)) installed.add(norm(f));
                }
            } catch (Throwable e) {
                // no font subsystem: report every font as unknown rather than pretending they exist
            }
            installedFonts = installed;
        }
        return installed.contains(norm(family));
    }

    private static String norm(String f) { return f.toLowerCase(Locale.ROOT).replace(" ", ""); }

    private static double twipsToMm(Object twips) {
        double t = twips instanceof Number num ? num.doubleValue() : Double.parseDouble(String.valueOf(twips));
        return Math.round(t / 1440.0 * 25.4 * 10) / 10.0;
    }
}
