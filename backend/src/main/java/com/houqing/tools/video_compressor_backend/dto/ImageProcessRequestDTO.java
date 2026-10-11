package com.houqing.tools.video_compressor_backend.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import java.util.List;

@Data
public class ImageProcessRequestDTO {
    // 待处理的文件名列表
    private List<String> filenames;
    // 使用的工具: "convert", "resize", "compress"
    private String tool;
    // 对应工具的设置, 使用 JsonNode 可以灵活接收任何结构的 JSON 对象
    private JsonNode settings;
}