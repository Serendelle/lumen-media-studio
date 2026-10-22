package com.houqing.tools.video_compressor_backend.collaboration;

import java.util.Set;

/** An external editing provider. Link validation never fetches external content. */
public interface DocumentProvider {
    record Link(String url, String documentType) {}

    String id();

    Link validate(String url);

    Set<String> importExtensions(String documentType);
}
