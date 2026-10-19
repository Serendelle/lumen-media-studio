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

    public ConversionMatrixService(ToolLocator tools, ObjectMapper mapper) {
        this.tools = tools;
        this.mapper = mapper;
    }

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

    public boolean documentConvertAvailable() {
        return tools.available(Tool.SOFFICE) || tools.available(Tool.CHROMIUM);
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
