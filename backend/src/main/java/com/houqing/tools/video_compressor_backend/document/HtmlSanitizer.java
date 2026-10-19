package com.houqing.tools.video_compressor_backend.document;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an uploaded HTML file into a self-contained static page before the browser ever sees it: no scripts, no
 * frames/objects, no event handlers, no URL that could reach the network or the local disk (only data: images and
 * in-page anchors survive). A strict CSP is injected as a second fence, and the print page setup is added as CSS.
 * Every removal is counted so the conversion report can say exactly what was dropped.
 */
public final class HtmlSanitizer {
    private HtmlSanitizer() {}

    public record Result(String html, Map<String, Integer> removed, String title) {
        public int totalRemoved() { return removed.values().stream().mapToInt(Integer::intValue).sum(); }
    }

    private static final String DROP_TAGS = "script, noscript, iframe, frame, frameset, object, embed, applet, base, link, "
            + "portal, template, audio, video, source, track, foreignObject";
    private static final List<String> URL_ATTRS = List.of("src", "href", "srcset", "poster", "background", "data", "action",
            "formaction", "xlink:href", "ping", "cite", "longdesc", "manifest", "icon", "lowsrc", "dynsrc");
    private static final Pattern CSS_URL = Pattern.compile("(?i)url\\s*\\(\\s*(['\"]?)(.*?)\\1\\s*\\)");
    private static final Pattern CSS_IMPORT = Pattern.compile("(?i)@import[^;]*;?");
    private static final Pattern CSS_EXPR = Pattern.compile("(?i)expression\\s*\\(|behavior\\s*:|-moz-binding");

    public static final String CSP = "default-src 'none'; img-src data:; style-src 'unsafe-inline'; font-src data:";

    /**
     * @param pageCss e.g. "@page { size: A4 portrait; margin: 10mm }" or null to leave the page setup alone
     */
    public static Result sanitize(String html, String pageCss) {
        Map<String, Integer> removed = new LinkedHashMap<>();
        Document doc = Jsoup.parse(html);
        doc.outputSettings().prettyPrint(false).charset("UTF-8");

        for (Element e : doc.select(DROP_TAGS)) {
            String tag = e.normalName();
            if (tag.equals("script") || tag.equals("noscript")) removed.merge("脚本", 1, Integer::sum);
            else if (tag.equals("link")) removed.merge("外部样式/资源链接", 1, Integer::sum);
            else removed.merge("嵌入对象（" + tag + "）", 1, Integer::sum);
            e.remove();
        }
        for (Element meta : doc.select("meta[http-equiv]")) {
            meta.remove();
            removed.merge("meta 指令", 1, Integer::sum);
        }

        for (Element e : doc.getAllElements()) {
            List<String> drop = new ArrayList<>();
            for (Attribute a : e.attributes()) {
                String key = a.getKey().toLowerCase(Locale.ROOT);
                String val = a.getValue().trim().toLowerCase(Locale.ROOT).replaceAll("\\s", "");
                if (key.startsWith("on")) {
                    drop.add(a.getKey());
                    removed.merge("事件脚本属性", 1, Integer::sum);
                } else if (URL_ATTRS.contains(key)) {
                    boolean keep = val.startsWith("#") || (key.equals("src") && val.startsWith("data:image/"))
                            || (key.equals("href") && val.isEmpty());
                    if (!keep) {
                        drop.add(a.getKey());
                        removed.merge(val.startsWith("javascript:") ? "javascript: 链接" : "外部或本地文件引用", 1, Integer::sum);
                    }
                } else if (val.startsWith("javascript:") || val.startsWith("vbscript:")) {
                    drop.add(a.getKey());
                    removed.merge("javascript: 链接", 1, Integer::sum);
                } else if (key.equals("style")) {
                    String cleaned = cleanCss(a.getValue(), removed);
                    if (!cleaned.equals(a.getValue())) a.setValue(cleaned);
                }
            }
            drop.forEach(e::removeAttr);
        }
        for (Element style : doc.select("style")) {
            String css = style.data();
            String cleaned = cleanCss(css, removed);
            if (!cleaned.equals(css)) style.text(cleaned);
        }

        Element head = doc.head();
        head.prependElement("meta").attr("http-equiv", "Content-Security-Policy").attr("content", CSP);
        head.prependElement("meta").attr("charset", "utf-8");
        if (pageCss != null && !pageCss.isBlank()) head.appendElement("style").text(pageCss);
        return new Result(doc.outerHtml(), removed, doc.title());
    }

    static String cleanCss(String css, Map<String, Integer> removed) {
        String out = css;
        Matcher imp = CSS_IMPORT.matcher(out);
        if (imp.find()) {
            removed.merge("CSS @import", 1, Integer::sum);
            out = CSS_IMPORT.matcher(out).replaceAll("");
        }
        Matcher m = CSS_URL.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String target = m.group(2).trim().toLowerCase(Locale.ROOT);
            if (target.startsWith("data:")) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            } else {
                removed.merge("CSS 外部资源", 1, Integer::sum);
                m.appendReplacement(sb, "none");
            }
        }
        m.appendTail(sb);
        out = sb.toString();
        if (CSS_EXPR.matcher(out).find()) {
            removed.merge("CSS 脚本表达式", 1, Integer::sum);
            out = CSS_EXPR.matcher(out).replaceAll("/*removed*/");
        }
        return out;
    }
}
