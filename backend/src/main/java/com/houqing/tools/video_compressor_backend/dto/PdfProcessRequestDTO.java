package com.houqing.tools.video_compressor_backend.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import java.util.List;

@Data
public class PdfProcessRequestDTO {
    // 待处理的文件名列表 (PDF 或图片)
    private List<String> filenames;
    // 使用的工具: "pdf-to-image", "images-to-pdf"
    private String tool;
    // 对应工具的设置
    private JsonNode settings;
}