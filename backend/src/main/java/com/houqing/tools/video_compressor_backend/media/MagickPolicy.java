package com.houqing.tools.video_compressor_backend.media;

import com.houqing.tools.video_compressor_backend.config.AppProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Extracts the bundled restrictive ImageMagick policy.xml to the data dir so MAGICK_CONFIGURE_PATH can point at it. */
@Component
public class MagickPolicy {
    private final Path dir;

    public MagickPolicy(AppProperties props) {
        this.dir = props.dataRoot().resolve("imagemagick");
        try (InputStream in = new ClassPathResource("imagemagick/policy.xml").getInputStream()) {
            Files.createDirectories(dir);
            Files.copy(in, dir.resolve("policy.xml"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot install ImageMagick policy", e);
        }
    }

    public Path directory() { return dir; }
}
