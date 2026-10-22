package com.houqing.tools.video_compressor_backend.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.config.AppProperties;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.*;

/**
 * Reads metadata with ExifTool (-json -G1, UTF-8) and summarises the privacy-sensitive fields. Synchronous
 * read used by both modes; writing/removal go through the task engine so they produce downloadable files.
 */
@Service
public class MetadataService {
    private static final List<String> SENSITIVE_HINTS = List.of(
            "gps", "serial", "owner", "author", "creator", "artist", "location",
            "cameraserial", "lensserialnumber", "hostcomputer", "software");

    private final ExifToolRunner exif;
    private final StorageService storage;
    private final Path argDir;

    public MetadataService(ExifToolRunner exif, StorageService storage, AppProperties props) {
        this.exif = exif; this.storage = storage; this.argDir = props.dataRoot().resolve("tmp");
    }

    public Map<String, Object> view(long ownerId, String fileId) {
        MediaFile mf = storage.require(ownerId, fileId);
        Path path = storage.pathOf(mf);

        JsonNode node;
        try { node = exif.readJson(path, argDir, null); }
        catch (ApiException e) {
            // Preserve the metadata-view contract while writers retain their PROCESS_FAILED status.
            if (e.getCode() == ErrorCode.PROCESS_FAILED) throw new ApiException(ErrorCode.MEDIA_UNREADABLE);
            throw e;
        }

        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        List<String> sensitive = new ArrayList<>();
        Double lat = null, lon = null;
        var it = node.fields();
        while (it.hasNext()) {
            var e = it.next();
            String key = e.getKey();
            if (key.equals("SourceFile") || key.startsWith("ExifTool:")) continue;
            String[] parts = key.split(":", 2);
            String group = parts.length == 2 ? parts[0] : "File";
            String tag = parts.length == 2 ? parts[1] : key;
            Object value = jsonValue(e.getValue());
            groups.computeIfAbsent(group, k -> new LinkedHashMap<>()).put(tag, value);
            String lower = tag.toLowerCase(Locale.ROOT);
            if (SENSITIVE_HINTS.stream().anyMatch(lower::contains)) sensitive.add(key);
            if (lower.equals("gpslatitude")) lat = asDouble(value);
            if (lower.equals("gpslongitude")) lon = asDouble(value);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fileId", mf.getId());
        result.put("filename", mf.getOriginalName());
        result.put("mediaType", mf.getMediaType());
        result.put("groups", groups);
        result.put("sensitiveTags", sensitive);
        result.put("hasPrivacyData", !sensitive.isEmpty());
        if (lat != null && lon != null) {
            Map<String, Object> gps = new LinkedHashMap<>();
            gps.put("latitude", lat);
            gps.put("longitude", lon);
            gps.put("mapUrl", amapLink(lat, lon));
            result.put("gps", gps);
        }
        return result;
    }

    private static Object jsonValue(JsonNode v) {
        if (v.isNumber()) return v.numberValue();
        if (v.isBoolean()) return v.booleanValue();
        if (v.isArray() || v.isObject()) return v.toString();
        return v.asText();
    }

    private static Double asDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return null; }
    }

    /** Builds an Amap marker link, converting WGS-84 to GCJ-02 as Chinese maps expect. */
    static String amapLink(double wgsLat, double wgsLon) {
        double[] gcj = GeoConvert.wgs84ToGcj02(wgsLat, wgsLon);
        return String.format(Locale.ROOT,
                "https://uri.amap.com/marker?position=%f,%f&name=%s&src=lumiere&callnative=0",
                gcj[1], gcj[0], "拍摄地点");
    }
}
