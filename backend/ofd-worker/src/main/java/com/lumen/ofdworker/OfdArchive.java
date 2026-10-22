package com.lumen.ofdworker;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipFile;

/** Local-only, bounded extraction. OFDRW never receives the original ZIP. */
final class OfdArchive {
    private static final Set<String> REFERENCES = Set.of("DocRoot", "PublicRes", "DocumentRes", "Annotations",
            "Signatures", "Attachments", "MediaFile", "FontFile", "ColorSpaceFile", "FileLoc", "FileRef", "BaseLoc");
    private static final Set<String> XML_REFERENCES = Set.of("DocRoot", "PublicRes", "DocumentRes", "Annotations", "Signatures", "Attachments", "FileLoc");

    private OfdArchive() {}

    static Path extract(Path source, Path directory) throws Exception {
        Path root = directory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        long total = 0;
        long totalXml = 0;
        int xmlNodes = 0;
        int count = 0;
        Set<String> names = new HashSet<>();
        try (ZipFile zip = new ZipFile(source.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName();
                if (++count > 5000 || !names.add(name.toLowerCase(Locale.ROOT)) || name.isBlank()
                        || name.startsWith("/") || name.contains("\\") || name.contains(":") || name.indexOf(0) >= 0) invalid();
                for (String part : name.split("/")) if (part.isEmpty() || part.endsWith(".") || part.endsWith(" ")) invalid();
                Path target = root.resolve(name).normalize();
                if (!target.startsWith(root)) invalid();
                if (entry.isDirectory()) { Files.createDirectories(target); continue; }
                Files.createDirectories(target.getParent());
                boolean xml = name.toLowerCase(Locale.ROOT).endsWith(".xml");
                long size = 0;
                ByteArrayOutputStream xmlBytes = xml ? new ByteArrayOutputStream() : null;
                try (var input = zip.getInputStream(entry); var output = Files.newOutputStream(target)) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = input.read(buffer)) != -1) {
                        size += n;
                        total += n;
                        if (xml) totalXml += n;
                        if (total > 256L * 1024 * 1024 || totalXml > 32L * 1024 * 1024 || (xml && size > 4 * 1024 * 1024)) invalid();
                        output.write(buffer, 0, n);
                        if (xml) xmlBytes.write(buffer, 0, n);
                    }
                }
                if (size > 1024 * 1024 && size > Math.max(1, entry.getCompressedSize()) * 200) invalid();
                if (xml) {
                    xmlNodes += validateXml(xmlBytes.toByteArray(), target, root);
                    if (xmlNodes > 250_000) invalid();
                }
            }
        }
        if (!Files.isRegularFile(root.resolve("OFD.xml"))) invalid();
        return root;
    }

    private static int validateXml(byte[] bytes, Path file, Path root) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", "128");
        factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/elementAttributeLimit", "128");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
        var document = builder.parse(new ByteArrayInputStream(bytes));
        Element xmlRoot = document.getDocumentElement();
        Path resourceBase = "Res".equals(xmlRoot.getLocalName()) && xmlRoot.hasAttribute("BaseLoc")
                ? reference(xmlRoot.getAttribute("BaseLoc"), file, root) : null;
        NodeList elements = document.getElementsByTagName("*");
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            String localName = element.getLocalName() == null ? element.getTagName() : element.getLocalName();
            if (REFERENCES.contains(localName) && !hasElementChildren(element) && !element.getTextContent().isBlank()) {
                if (XML_REFERENCES.contains(localName) && !element.getTextContent().trim().toLowerCase(Locale.ROOT).endsWith(".xml")) invalid();
                reference(element.getTextContent(), file, root);
                if (resourceBase != null) reference(element.getTextContent(), resourceBase.resolve("_resource_"), root);
            }
            for (String attr : Set.of("BaseLoc", "FileLoc", "FileRef", "href")) {
                if (element.hasAttribute(attr)) reference(element.getAttribute(attr), file, root);
            }
            if (Set.of("Page", "TemplatePage").contains(localName) && element.hasAttribute("BaseLoc")
                    && !element.getAttribute("BaseLoc").toLowerCase(Locale.ROOT).endsWith(".xml")) invalid();
        }
        // Signature packages may contain nested OFD seals. Conversion does not transfer signature validity;
        // removing their entry point also prevents an unbounded recursive ZIP parse in the renderer.
        NodeList signatures = document.getElementsByTagNameNS("*", "Signatures");
        boolean changed = signatures.getLength() > 0;
        while (signatures.getLength() > 0) {
            var signature = signatures.item(0);
            signature.getParentNode().removeChild(signature);
        }
        if (changed) {
            var tf = TransformerFactory.newInstance();
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            tf.newTransformer().transform(new DOMSource(document), new StreamResult(file.toFile()));
        }
        return elements.getLength();
    }

    private static Path reference(String value, Path file, Path root) throws IOException {
        String ref = value.trim();
        if (ref.isEmpty() || ref.contains(":") || ref.contains("\\") || ref.contains("%") || ref.indexOf(0) >= 0) invalid();
        Path target = ref.startsWith("/") ? root.resolve(ref.substring(1)) : file.getParent().resolve(ref);
        if (!target.normalize().startsWith(root)) invalid();
        return target.normalize();
    }

    private static boolean hasElementChildren(Element element) {
        for (var child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element) return true;
        }
        return false;
    }

    private static void invalid() throws IOException {
        throw new IOException("Unsafe or invalid OFD package");
    }
}
