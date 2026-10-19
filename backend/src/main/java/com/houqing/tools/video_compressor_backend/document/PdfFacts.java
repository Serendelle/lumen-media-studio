package com.houqing.tools.video_compressor_backend.document;

import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Facts read from a PDF with PDFBox (no external tool): pages, sizes, fonts, encryption, and page rendering. */
public final class PdfFacts {
    private PdfFacts() {}

    public record Info(int pageCount, double firstWidthMm, double firstHeightMm, String pageSizeLabel,
                       boolean ownerRestricted, Set<String> fonts) {}

    static final int FONT_SCAN_PAGES = 50;

    public static boolean hasPdfMagic(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] b = in.readNBytes(5);
            return b.length == 5 && new String(b, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-");
        } catch (IOException e) {
            return false;
        }
    }

    /** Opens a PDF with disk-backed buffers; a user password -> DOCUMENT_PASSWORD_REQUIRED, garbage -> DOCUMENT_CORRUPTED. */
    public static PDDocument open(Path file) {
        if (!hasPdfMagic(file)) throw new ApiException(ErrorCode.DOCUMENT_CORRUPTED, "不是有效的 PDF 文件");
        try {
            return Loader.loadPDF(file.toFile(), "", null, null, IOUtils.createTempFileOnlyStreamCache());
        } catch (InvalidPasswordException e) {
            throw new ApiException(ErrorCode.DOCUMENT_PASSWORD_REQUIRED);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.DOCUMENT_CORRUPTED, "PDF 结构已损坏，无法解析");
        }
    }

    public static Info read(Path file) {
        try (PDDocument doc = open(file)) {
            int pages = doc.getNumberOfPages();
            double wPt = 0, hPt = 0;
            if (pages > 0) {
                PDRectangle box = doc.getPage(0).getMediaBox();
                wPt = box.getWidth();
                hPt = box.getHeight();
            }
            double w = wPt / 72 * 25.4, h = hPt / 72 * 25.4;
            Set<String> fonts = new TreeSet<>();
            int scanned = 0;
            for (PDPage p : doc.getPages()) {
                if (scanned++ >= FONT_SCAN_PAGES) break;
                collectFonts(p.getResources(), fonts);
            }
            return new Info(pages, round1(w), round1(h), sizeLabel(wPt, hPt), doc.isEncrypted(), fonts);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.DOCUMENT_CORRUPTED, "PDF 结构已损坏，无法解析");
        }
    }

    private static void collectFonts(PDResources res, Set<String> out) {
        if (res == null) return;
        for (COSName n : res.getFontNames()) {
            try {
                PDFont f = res.getFont(n);
                if (f != null && f.getName() != null) {
                    String name = f.getName();
                    int plus = name.indexOf('+'); // subset prefix "ABCDEF+SimSun"
                    out.add(plus == 6 ? name.substring(7) : name);
                }
            } catch (IOException ignored) {
                // unreadable font dictionary: not fatal for facts
            }
        }
    }

    /** Renders one page (1-based) to PNG, capped to maxPixels on the longer edge. */
    public static byte[] renderPng(Path file, int page, float dpi, int maxPixels) {
        try (PDDocument doc = open(file)) {
            if (page < 1 || page > doc.getNumberOfPages()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "页码超出范围（共 " + doc.getNumberOfPages() + " 页）");
            }
            PDRectangle box = doc.getPage(page - 1).getMediaBox();
            float longest = Math.max(box.getWidth(), box.getHeight()) / 72f;
            float effective = longest <= 0 ? dpi : Math.min(dpi, maxPixels / longest);
            BufferedImage img = new PDFRenderer(doc).renderImageWithDPI(page - 1, Math.max(10, effective), ImageType.RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new ApiException(ErrorCode.DOCUMENT_CORRUPTED, "PDF 第 " + page + " 页无法渲染");
        }
    }

    /** Renders and returns whether the page has any non-white pixel (an all-white page is suspicious, not fatal). */
    public static boolean renderHasInk(Path file, int page) {
        try (PDDocument doc = open(file)) {
            BufferedImage img = new PDFRenderer(doc).renderImageWithDPI(page - 1, 24, ImageType.RGB);
            for (int y = 0; y < img.getHeight(); y += 2) {
                for (int x = 0; x < img.getWidth(); x += 2) {
                    if ((img.getRGB(x, y) & 0xFFFFFF) < 0xF0F0F0) return true;
                }
            }
            return false;
        } catch (IOException e) {
            throw new ApiException(ErrorCode.DOCUMENT_CORRUPTED, "PDF 第 " + page + " 页无法渲染");
        }
    }

    static String sizeLabel(double wPt, double hPt) {
        double w = Math.min(wPt, hPt) / 72 * 25.4, h = Math.max(wPt, hPt) / 72 * 25.4;
        String orient = wPt > hPt ? "横向" : "纵向";
        if (near(w, 210) && near(h, 297)) return "A4 " + orient;
        if (near(w, 297) && near(h, 420)) return "A3 " + orient;
        if (near(w, 148) && near(h, 210)) return "A5 " + orient;
        if (near(w, 215.9) && near(h, 279.4)) return "Letter " + orient;
        return Math.round(w) + "×" + Math.round(h) + " mm " + orient;
    }

    private static boolean near(double a, double b) { return Math.abs(a - b) < 3; }

    private static double round1(double v) { return Math.round(v * 10) / 10.0; }
}
