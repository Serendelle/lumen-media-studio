package com.houqing.tools.video_compressor_backend.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.media.Tool;
import com.houqing.tools.video_compressor_backend.media.ToolLocator;
import org.springframework.stereotype.Service;

import java.util.List;

/** The conversion routes this installation can really run right now — shared by the UI and the AI agents. */
@Service
public class ConversionMatrixService {
    private final ToolLocator tools;
    private final ObjectMapper mapper;
    private final OfdConversionService ofd;

    public ConversionMatrixService(ToolLocator tools, ObjectMapper mapper, OfdConversionService ofd) {
        this.tools = tools;
        this.mapper = mapper;
        this.ofd = ofd;
    }

    public ObjectNode matrix() {
        boolean office = tools.available(Tool.SOFFICE);
        boolean browser = tools.available(Tool.CHROMIUM);
        ObjectNode n = mapper.createObjectNode();
        ArrayNode routes = n.putArray("routes");
        for (String from : UniversalConversionService.FORMATS) for (String to : UniversalConversionService.FORMATS) {
            if (from.equals(to)) continue;
            boolean textTarget = List.of("docx", "xlsx", "html").contains(to);
            boolean fixed = List.of("pdf", "ofd", "png", "jpg").contains(from);
            String mode = fixed || !textTarget ? "layout" : "editable";
            boolean bridge = UniversalConversionService.needsLegacyPdf(from, to, mode);
            boolean available = (!(from.equals("ofd") || to.equals("ofd")) || ofd.available())
                    && (!bridge || (from.equals("html") ? browser : office));
            String reason = available ? "" : (from.equals("ofd") || to.equals("ofd")) && !ofd.available()
                    ? "未安装 OFD worker" : from.equals("html") ? "未找到 Chrome / Edge" : "未安装 LibreOffice";
            ObjectNode r = routes.addObject();
            r.put("from", from); r.put("to", to); r.put("engine", bridge ? from.equals("html") ? "Chromium / PDFBox" : "LibreOffice / PDFBox"
                    : from.equals("ofd") || to.equals("ofd") ? "OFDRW（隔离进程）/ PDFBox" : "PDFBox / Apache POI / ImageIO");
            r.put("available", available); r.put("reason", reason); r.put("taskType", "document.convert");
            r.put("defaultMode", mode);
            var modes = r.putArray("modes");
            if (textTarget) { if (fixed) { modes.add("layout"); if (from.equals("pdf")) modes.add("editable"); }
                else { modes.add("editable"); if (from.equals("html") ? browser : office) modes.add("layout"); } }
            else modes.add("layout");
            r.put("outputKind", UniversalConversionService.image(to) ? UniversalConversionService.image(from) ? "IMAGE" : "ARCHIVE"
                    : to.equals("pdf") ? "PDF" : "DOCUMENT");
            r.put("description", textTarget && fixed ? UniversalConversionService.image(from)
                    ? "图片嵌入页面，不进行 OCR" : from.equals("ofd") ? "以逐页图片保留外观，不含可编辑文字层" : "默认逐页图片保留外观；可选提取文本层（不含 OCR）"
                    : textTarget ? "提取段落、可见单元格和表格，复杂排版可能变化"
                    : UniversalConversionService.image(to) && !UniversalConversionService.image(from) ? "逐页图片 ZIP，含页序与 SHA-256 清单"
                    : to.equals("ofd") ? "逐页图像封装 OFD，保留外观，不保留数字签名效力" : "页面转换");
        }
        ArrayNode no = n.putArray("notSupported");
        for (String s : List.of("旧版 .doc / .xls（请先另存为 DOCX / XLSX）", "扫描件及图片 OCR", "数字签名效力迁移", "复杂排版的无损语义还原")) no.add(s);
        n.put("maxInputs", 100); n.put("maxRenderPages", 100); n.put("maxTotalBytes", 200L * 1024 * 1024);
        return n;
    }

    public boolean documentConvertAvailable() {
        return true;
    }

    private static void route(ArrayNode a, String from, String to, String engine, boolean available, String taskType) {
        ObjectNode r = a.addObject();
        r.put("from", from);
        r.put("to", to);
        r.put("engine", engine);
        r.put("available", available);
        r.put("taskType", taskType);
    }
}
