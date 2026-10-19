package com.houqing.tools.video_compressor_backend.document;

import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Deterministic structural check of an OOXML package (.docx / .xlsx) before any parser touches it. A ZIP renamed to
 * .docx, a macro workbook, a zip bomb, a "../" entry or a relationship that makes Office fetch remote content is
 * rejected here. It never calls a language model and never extracts anything to disk.
 */
public final class OoxmlInspector {
    private OoxmlInspector() {}

    public enum Format { DOCX, XLSX }

    static final int MAX_ENTRIES = 5000;
    static final long MAX_UNCOMPRESSED = 250L * 1024 * 1024;
    static final int MAX_RATIO = 100;
    private static final long MAX_PART_SCAN = 4L * 1024 * 1024;

    private static final Pattern RELATIONSHIP = Pattern.compile("<Relationship\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR_TYPE = Pattern.compile("\\bType\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern ATTR_MODE = Pattern.compile("\\bTargetMode\\s*=\\s*\"External\"", Pattern.CASE_INSENSITIVE);
    /** External targets Office would actually load (templates, OLE, linked images, frames, workbook links...). */
    private static final List<String> SAFE_EXTERNAL_TYPES = List.of("/hyperlink");

    /**
     * Validates the package and returns its real format, which must agree with the declared extension.
     *
     * @param declaredExt extension of the client's file name (lower case, may be empty)
     */
    public static Format validate(Path file, String declaredExt) {
        if (declaredExt.equals("docm") || declaredExt.equals("xlsm") || declaredExt.equals("dotm") || declaredExt.equals("xltm")) {
            throw new ApiException(ErrorCode.UNSUPPORTED_DOCUMENT, "不接受启用宏的文档（." + declaredExt + "）");
        }
        long fileSize;
        try {
            fileSize = Files.size(file);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.DOCUMENT_CORRUPTED);
        }
        boolean contentTypes = false, word = false, workbook = false;
        long total = 0;
        int count = 0;
        try (ZipFile zip = new ZipFile(file.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (++count > MAX_ENTRIES) throw corrupted("文档内部条目超过 " + MAX_ENTRIES + " 个");
                String name = e.getName();
                if (unsafeName(name)) throw corrupted("文档内部包含非法路径");
                long size = e.getSize();
                if (size < 0) throw corrupted("文档内部条目缺少大小信息");
                total += size;
                if (total > MAX_UNCOMPRESSED || total > fileSize * MAX_RATIO) {
                    throw corrupted("文档解压后体积异常（疑似压缩炸弹）");
                }
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.equals("[content_types].xml")) contentTypes = true;
                if (lower.equals("word/document.xml")) word = true;
                if (lower.equals("xl/workbook.xml")) workbook = true;
                if (lower.endsWith("vbaproject.bin")) {
                    throw new ApiException(ErrorCode.UNSUPPORTED_DOCUMENT, "文档包含宏代码，不予接受");
                }
            }
            if (!contentTypes || (!word && !workbook)) {
                throw new ApiException(ErrorCode.UNSUPPORTED_DOCUMENT, "不是有效的 Word（.docx）或 Excel（.xlsx）文档");
            }
            String types = readPart(zip, zip.getEntry("[Content_Types].xml"));
            checkXml(types);
            if (types.toLowerCase(Locale.ROOT).contains("macroenabled")) {
                throw new ApiException(ErrorCode.UNSUPPORTED_DOCUMENT, "文档为启用宏的格式，不予接受");
            }
            en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.getName().toLowerCase(Locale.ROOT).endsWith(".rels")) continue;
                checkRelationships(readPart(zip, e));
            }
        } catch (ZipException e) {
            throw corrupted("文档压缩结构已损坏");
        } catch (IOException e) {
            throw corrupted("无法读取文档");
        }
        Format real = word ? Format.DOCX : Format.XLSX;
        String expected = real == Format.DOCX ? "docx" : "xlsx";
        if (!declaredExt.isEmpty() && !declaredExt.equals(expected)) {
            throw new ApiException(ErrorCode.UNSUPPORTED_DOCUMENT,
                    "文件扩展名（." + declaredExt + "）与真实内容（." + expected + "）不一致");
        }
        return real;
    }

    static boolean unsafeName(String name) {
        if (name.isEmpty() || name.startsWith("/") || name.startsWith("\\") || name.contains("\\")) return true;
        if (name.length() > 1 && name.charAt(1) == ':') return true;
        for (String seg : name.split("/")) if (seg.equals("..")) return true;
        return name.indexOf('\0') >= 0;
    }

    private static void checkRelationships(String xml) {
        checkXml(xml);
        Matcher m = RELATIONSHIP.matcher(xml);
        while (m.find()) {
            String rel = m.group();
            if (!ATTR_MODE.matcher(rel).find()) continue;
            Matcher t = ATTR_TYPE.matcher(rel);
            String type = t.find() ? t.group(1) : "";
            if (SAFE_EXTERNAL_TYPES.stream().noneMatch(type::endsWith)) {
                String shortType = type.substring(type.lastIndexOf('/') + 1);
                throw new ApiException(ErrorCode.UNSUPPORTED_DOCUMENT,
                        "文档引用了外部资源（" + shortType + "），请先在 Office 中断开外部链接后再上传");
            }
        }
    }

    /** No DTDs anywhere in the package parts we read: a DOCTYPE is how XML entity attacks start. */
    private static void checkXml(String xml) {
        if (xml.toLowerCase(Locale.ROOT).contains("<!doctype") || xml.contains("<!ENTITY")) {
            throw new ApiException(ErrorCode.UNSUPPORTED_DOCUMENT, "文档内部包含 XML 实体声明，不予接受");
        }
    }

    private static String readPart(ZipFile zip, ZipEntry e) throws IOException {
        if (e == null) return "";
        try (InputStream in = zip.getInputStream(e)) {
            byte[] b = in.readNBytes((int) MAX_PART_SCAN);
            return new String(b, StandardCharsets.UTF_8);
        }
    }

    private static ApiException corrupted(String msg) {
        return new ApiException(ErrorCode.DOCUMENT_CORRUPTED, msg);
    }
}
