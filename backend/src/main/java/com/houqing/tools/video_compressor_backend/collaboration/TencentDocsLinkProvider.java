package com.houqing.tools.video_compressor_backend.collaboration;

import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Free link association only; no iframe, scraping, credentials, or unofficial Tencent API. */
@Component
public class TencentDocsLinkProvider implements DocumentProvider {
    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("doc", "DOCUMENT"), Map.entry("sheet", "SPREADSHEET"),
            Map.entry("slide", "SLIDE"), Map.entry("form", "OTHER"),
            Map.entry("smartsheet", "SPREADSHEET"), Map.entry("mind", "OTHER"),
            Map.entry("flowchart", "OTHER"), Map.entry("board", "OTHER"), Map.entry("pdf", "OTHER"));
    private static final Set<String> QUERY_KEYS = Set.of("tab", "u", "_t", "scode", "nlc", "preview", "scene", "from", "is_mainbar");

    @Override public String id() { return "TENCENT_DOCS_LINK"; }

    @Override public Link validate(String input) {
        if (input == null || input.length() > 2048 || !input.equals(input.trim())
                || input.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c))) throw invalid();
        try {
            URI uri = new URI(input);
            // Comparing the exact parsed host prevents suffix, user-info, port, Unicode and encoded-host tricks.
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.isOpaque()
                    || uri.getRawUserInfo() != null || uri.getPort() != -1
                    || uri.getHost() == null || !"docs.qq.com".equalsIgnoreCase(uri.getHost())
                    || !uri.getRawAuthority().equalsIgnoreCase("docs.qq.com")) throw invalid();
            String path = uri.getRawPath();
            if (path == null || !path.matches("/[a-z]+/[A-Za-z0-9_-]{4,200}/?")) throw invalid();
            String kind = path.substring(1, path.indexOf('/', 1));
            String type = TYPES.get(kind);
            if (type == null) throw invalid();
            String query = uri.getRawQuery();
            if (query != null) {
                if (query.length() > 1024 || query.isEmpty()) throw invalid();
                Set<String> seen = new java.util.HashSet<>();
                for (String part : query.split("&", -1)) {
                    int split = part.indexOf('=');
                    if (split < 1) throw invalid();
                    String key = part.substring(0, split);
                    String value = part.substring(split + 1);
                    if (!QUERY_KEYS.contains(key) || !seen.add(key) || !value.matches("[A-Za-z0-9_.~-]{0,256}")) throw invalid();
                }
            }
            String fragment = uri.getRawFragment();
            if (fragment != null && !fragment.matches("[A-Za-z0-9_=.-]{0,256}")) throw invalid();
            return new Link("https://docs.qq.com" + path + (query == null ? "" : "?" + query)
                    + (fragment == null ? "" : "#" + fragment), type);
        } catch (URISyntaxException e) {
            throw invalid();
        }
    }

    @Override public Set<String> importExtensions(String documentType) {
        return switch (documentType == null ? "" : documentType.toUpperCase(Locale.ROOT)) {
            case "DOCUMENT" -> Set.of("docx", "pdf");
            case "SPREADSHEET" -> Set.of("xlsx", "pdf");
            case "SLIDE" -> Set.of("pdf");
            case "OTHER" -> Set.of("docx", "xlsx", "pdf");
            default -> throw new ApiException(ErrorCode.VALIDATION_FAILED, "文档类型不正确");
        };
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "请填写 https://docs.qq.com 下的腾讯文档直接链接，不支持跳转链接或其他网站");
    }
}
