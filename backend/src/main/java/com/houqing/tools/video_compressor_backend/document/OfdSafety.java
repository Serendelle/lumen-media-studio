package com.houqing.tools.video_compressor_backend.document;

import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Validates the actual OFD package before any third-party parser can extract it. */
public final class OfdSafety {
    public static final int MAX_PAGES = 200;
    private static final long MAX_EXPANDED = 256L * 1024 * 1024;
    private static final int MAX_XML = 4 * 1024 * 1024;
    private static final Set<String> REFERENCES = Set.of("DocRoot", "PublicRes", "DocumentRes", "Annotations",
            "Signatures", "Attachments", "MediaFile", "FontFile", "ColorSpaceFile", "FileLoc", "FileRef", "BaseLoc");
    private static final Set<String> XML_REFERENCES = Set.of("DocRoot", "PublicRes", "DocumentRes", "Annotations", "Signatures", "Attachments", "FileLoc");

    private OfdSafety() {}

    /** Returns the validated page count of a single-document OFD package. */
    public static int validate(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Set<String> pageXmlNames = new HashSet<>();
            Set<String> names = new HashSet<>();
            long expanded = 0;
            long xmlExpanded = 0;
            int xmlNodes = 0;
            Document root = null;
            int entries = 0;
            var enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                String name = entry.getName();
                safeEntry(name);
                if (++entries > 5000 || !names.add(name.toLowerCase(Locale.ROOT))) reject();
                if (entry.isDirectory()) continue;
                long size = 0;
                boolean isXml = name.toLowerCase(Locale.ROOT).endsWith(".xml");
                java.io.ByteArrayOutputStream bytes = isXml ? new java.io.ByteArrayOutputStream() : null;
                try (InputStream in = zip.getInputStream(entry)) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        size += n;
                        expanded += n;
                        if (isXml) xmlExpanded += n;
                        if (expanded > MAX_EXPANDED || xmlExpanded > 32L * 1024 * 1024 || (isXml && size > MAX_XML)) reject();
                        if (isXml) bytes.write(buffer, 0, n);
                    }
                }
                if (size > 1024 * 1024 && size > Math.max(1, entry.getCompressedSize()) * 200) reject();
                if (isXml) {
                    // Discard each parsed DOM after checking it; never retain all package XML in the server heap.
                    Document parsed = parse(bytes.toByteArray());
                    xmlNodes += parsed.getElementsByTagName("*").getLength();
                    if (xmlNodes > 250_000) reject();
                    validateReferences(name, parsed);
                    if ("OFD.xml".equals(name)) root = parsed;
                    if ("Page".equals(parsed.getDocumentElement().getLocalName())) pageXmlNames.add(name);
                }
            }
            if (root == null || !"OFD".equals(root.getDocumentElement().getLocalName())) reject();
            NodeList bodies = root.getElementsByTagNameNS("*", "DocBody");
            if (bodies.getLength() != 1) throw new ApiException(ErrorCode.UNSUPPORTED_DOCUMENT, "暂支持单文档 OFD 文件，请先拆分多文档包");
            NodeList roots = root.getElementsByTagNameNS("*", "DocRoot");
            if (roots.getLength() != 1) reject();
            String documentPath = resolve("OFD.xml", roots.item(0).getTextContent());
            ZipEntry documentEntry = zip.getEntry(documentPath);
            if (documentEntry == null || documentEntry.isDirectory() || documentEntry.getSize() > MAX_XML) reject();
            Document document;
            try (InputStream in = zip.getInputStream(documentEntry)) { document = parse(in.readNBytes(MAX_XML + 1)); }
            if (!"Document".equals(document.getDocumentElement().getLocalName())) reject();
            NodeList pages = document.getElementsByTagNameNS("*", "Page");
            if (pages.getLength() < 1 || pages.getLength() > MAX_PAGES) {
                throw new ApiException(ErrorCode.UNSUPPORTED_DOCUMENT, "OFD 转换支持 1—" + MAX_PAGES + " 页");
            }
            Set<String> pageIds = new HashSet<>();
            for (int i = 0; i < pages.getLength(); i++) {
                Element page = (Element) pages.item(i);
                if (page.getAttribute("ID").isBlank() || !pageIds.add(page.getAttribute("ID"))) reject();
                if (!pageXmlNames.contains(resolve(documentPath, page.getAttribute("BaseLoc")))) reject();
            }
            return pages.getLength();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(ErrorCode.DOCUMENT_CORRUPTED, "OFD 文件损坏或包含不安全的压缩包、XML 或资源引用");
        }
    }

    private static Document parse(byte[] bytes) throws Exception {
        if (bytes.length > MAX_XML) reject();
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        f.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", "128");
        f.setAttribute("http://www.oracle.com/xml/jaxp/properties/elementAttributeLimit", "128");
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        var builder = f.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
        return builder.parse(new ByteArrayInputStream(bytes));
    }

    private static void validateReferences(String source, Document document) {
        Element xmlRoot = document.getDocumentElement();
        String resourceBase = "Res".equals(xmlRoot.getLocalName()) && xmlRoot.hasAttribute("BaseLoc")
                ? resolve(source, xmlRoot.getAttribute("BaseLoc")) : null;
        NodeList elements = document.getElementsByTagName("*");
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            String localName = element.getLocalName() == null ? element.getTagName() : element.getLocalName();
            if (REFERENCES.contains(localName) && !hasElementChildren(element) && !element.getTextContent().isBlank()) {
                if (XML_REFERENCES.contains(localName) && !element.getTextContent().trim().toLowerCase(Locale.ROOT).endsWith(".xml")) reject();
                resolve(source, element.getTextContent());
                if (resourceBase != null) resolve(resourceBase.isEmpty() ? "_resource_" : resourceBase + "/_resource_", element.getTextContent());
            }
            for (String attribute : Set.of("BaseLoc", "FileLoc", "FileRef", "href")) {
                if (element.hasAttribute(attribute)) resolve(source, element.getAttribute(attribute));
            }
            if (Set.of("Page", "TemplatePage").contains(localName) && element.hasAttribute("BaseLoc")
                    && !element.getAttribute("BaseLoc").toLowerCase(Locale.ROOT).endsWith(".xml")) reject();
        }
    }

    private static boolean hasElementChildren(Element element) {
        for (var child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element) return true;
        }
        return false;
    }

    private static void safeEntry(String name) {
        if (name.isBlank() || name.startsWith("/") || name.contains("\\") || name.contains(":") || name.indexOf(0) >= 0) reject();
        for (String part : name.split("/")) if (part.isEmpty() || part.endsWith(".") || part.endsWith(" ")) reject();
    }

    private static String resolve(String source, String raw) {
        String ref = raw.trim();
        if (ref.isEmpty() || ref.contains(":") || ref.contains("\\") || ref.contains("%") || ref.indexOf(0) >= 0) reject();
        Path target = ref.startsWith("/") ? Path.of(ref.substring(1)) : Path.of(source).getParent() == null
                ? Path.of(ref) : Path.of(source).getParent().resolve(ref);
        target = target.normalize();
        if (target.isAbsolute() || target.startsWith("..")) reject();
        return target.toString().replace('\\', '/');
    }

    private static void reject() {
        throw new ApiException(ErrorCode.DOCUMENT_CORRUPTED, "OFD 文件损坏或包含不安全的压缩包、XML 或资源引用");
    }
}
