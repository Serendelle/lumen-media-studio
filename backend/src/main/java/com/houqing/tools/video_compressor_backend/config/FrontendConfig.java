package com.houqing.tools.video_compressor_backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Serves the built frontend (frontend/dist) from the backend, so a local deployment is one process and one URL
 * (http://localhost:8080). Hashed assets are cached long-term; index.html is never cached so updates show up.
 * Client-side routes (/studio, /login, …) fall back to index.html.
 */
@Configuration
public class FrontendConfig implements WebMvcConfigurer {
    private static final Logger log = LoggerFactory.getLogger(FrontendConfig.class);

    private final Path dist;

    public FrontendConfig(@Value("${app.frontend-dist:./frontend/dist}") String dist) {
        this.dist = Path.of(dist).toAbsolutePath().normalize();
        if (Files.isRegularFile(this.dist.resolve("index.html"))) log.info("Serving frontend from {}", this.dist);
        else log.info("No built frontend at {} (API only; run the frontend dev server or build it)", this.dist);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = dist.toUri().toString();
        registry.addResourceHandler("/assets/**")
                .addResourceLocations(location + "assets/")
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable());
        // Python sandbox runtime (WebAssembly CPython), same origin so no CDN is involved
        registry.addResourceHandler("/pyodide/**")
                .addResourceLocations(location + "pyodide/")
                .setCacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic());
        registry.addResourceHandler("/index.html", "/favicon.svg")
                .addResourceLocations(location)
                .setCacheControl(CacheControl.noCache());
    }

    /** .wasm must be served as application/wasm for streaming compilation in the browser. */
    @Override
    public void configureContentNegotiation(org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer c) {
        c.mediaType("wasm", org.springframework.http.MediaType.parseMediaType("application/wasm"));
    }

    /** SPA routes: forward to index.html so a refresh on /workspace/... (or the legacy /studio) works. */
    @Controller
    static class SpaFallback {
        @GetMapping({"/", "/login", "/register", "/collect/**", "/studio", "/studio/**", "/workspace", "/workspace/**", "/{page:[a-zA-Z0-9_-]+}"})
        public String index() {
            return "forward:/index.html";
        }
    }
}
