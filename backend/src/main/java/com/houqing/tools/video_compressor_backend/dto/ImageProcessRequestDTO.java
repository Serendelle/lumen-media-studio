package com.houqing.tools.video_compressor_backend.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class ImageProcessRequestDTO {
    @NotEmpty @Size(max = 50)
    private List<@NotBlank String> filenames;
    /** convert / resize / compress */
    @NotBlank
    private String tool;
    /** Tool settings, validated by the handler (see docs/api-contract.md). */
    private JsonNode settings;
}
