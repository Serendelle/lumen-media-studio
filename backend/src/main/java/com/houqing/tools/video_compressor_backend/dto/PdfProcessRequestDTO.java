package com.houqing.tools.video_compressor_backend.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class PdfProcessRequestDTO {
    @NotEmpty @Size(max = 200)
    private List<@NotBlank String> filenames;
    /** pdf-to-image / images-to-pdf */
    @NotBlank
    private String tool;
    private JsonNode settings;
}
