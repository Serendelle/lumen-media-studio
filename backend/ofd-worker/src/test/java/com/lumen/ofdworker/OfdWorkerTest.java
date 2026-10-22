package com.lumen.ofdworker;

import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ofdrw.font.Font;
import org.ofdrw.layout.OFDDoc;
import org.ofdrw.layout.element.Paragraph;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OfdWorkerTest {
    @TempDir Path directory;

    @Test
    void imagesBecomeRealOfdAndPdfWithPageSizesAndVisibleContent() throws Exception {
        Path imageFile = directory.resolve("input.png");
        BufferedImage image = new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(24, 96, 180));
        graphics.fillRect(0, 0, 300, 200);
        graphics.setColor(Color.YELLOW);
        graphics.fillRect(50, 50, 200, 100);
        graphics.dispose();
        ImageIO.write(image, "png", imageFile.toFile());
        Properties properties = new Properties();
        properties.setProperty("count", "2");
        for (int i = 0; i < 2; i++) {
            properties.setProperty("page." + i + ".file", imageFile.toString());
            properties.setProperty("page." + i + ".width", i == 0 ? "210" : "297");
            properties.setProperty("page." + i + ".height", i == 0 ? "297" : "210");
        }
        Path manifest = directory.resolve("pages.properties");
        try (var out = Files.newOutputStream(manifest)) { properties.store(out, "test"); }
        Path ofd = directory.resolve("document.ofd");
        assertEquals(2, OfdWorker.fromPages(manifest, ofd));
        try (var zip = new java.util.zip.ZipFile(ofd.toFile())) {
            assertNotNull(zip.getEntry("OFD.xml"));
            assertTrue(zip.size() > 5);
        }
        Path pdfFile = directory.resolve("document.pdf");
        assertEquals(2, OfdWorker.toPdf(ofd, pdfFile));
        try (PDDocument pdf = PDDocument.load(pdfFile.toFile(), MemoryUsageSetting.setupTempFileOnly())) {
            assertEquals(2, pdf.getNumberOfPages());
            assertEquals(210 * 72 / 25.4, pdf.getPage(0).getMediaBox().getWidth(), .1);
            assertEquals(297 * 72 / 25.4, pdf.getPage(1).getMediaBox().getWidth(), .1);
            BufferedImage rendered = new PDFRenderer(pdf).renderImage(0);
            Color center = new Color(rendered.getRGB(rendered.getWidth() / 2, rendered.getHeight() / 2));
            assertTrue(center.getRed() > 240 && center.getGreen() > 240 && center.getBlue() < 30, "Rendered content must survive both conversions");
        }
    }

    @Test
    void rejectsTraversalWithoutWritingOutsideExtractionRoot() throws Exception {
        Path archive = archive("../escape.xml", "<OFD/>");
        assertThrows(Exception.class, () -> OfdArchive.extract(archive, directory.resolve("extract")));
        assertFalse(Files.exists(directory.resolve("escape.xml")));
    }

    @Test
    void rejectsDoctypeAndNetworkResource() throws Exception {
        Path xxe = archive("OFD.xml", "<!DOCTYPE OFD [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><OFD>&x;</OFD>");
        assertThrows(Exception.class, () -> OfdArchive.extract(xxe, directory.resolve("xxe")));
        Path network = archive("OFD.xml", "<OFD><MediaFile>https://example.invalid/pixel.png</MediaFile></OFD>");
        assertThrows(Exception.class, () -> OfdArchive.extract(network, directory.resolve("network")));
    }

    @Test
    void resourceBaseAndRelativeReferenceCannotEscapeTogether() throws Exception {
        Path archive = archive("Doc_0/Res.xml", "<Res xmlns='http://www.ofdspec.org/2016' BaseLoc='..'><FontFile>../secret.ttf</FontFile></Res>");
        assertThrows(Exception.class, () -> OfdArchive.extract(archive, directory.resolve("resource")));
    }

    @Test
    void rejectsPathologicallyNestedXmlBeforeOfdrwParsesIt() throws Exception {
        Path archive = archive("OFD.xml", "<OFD>" + "<x>".repeat(150) + "text" + "</x>".repeat(150) + "</OFD>");
        assertThrows(Exception.class, () -> OfdArchive.extract(archive, directory.resolve("deep")));
    }

    @Test
    void xmlResourceCannotHideFromXmlValidationByOmittingSuffix() throws Exception {
        Path archive = archive("OFD.xml", "<OFD><DocBody><DocRoot>Doc_0/Document</DocRoot></DocBody></OFD>");
        assertThrows(Exception.class, () -> OfdArchive.extract(archive, directory.resolve("suffix")));
    }

    @Test
    void embeddedLatinTextAndVectorBorderRenderWithoutItext() throws Exception {
        Path font = firstInstalled("C:/Windows/Fonts/arial.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "/System/Library/Fonts/Supplemental/Arial.ttf");
        assertNotNull(font, "A TrueType font must be installed for the OFD text rendering regression");
        textAndVector("Lumen OFD text 42", new Font("LumenTestLatin", font), "latin");
    }

    @Test
    void embeddedChineseCollectionTextAndVectorBorderRenderWithoutItext() throws Exception {
        Path font = firstInstalled("C:/Windows/Fonts/simsun.ttc", "C:/Windows/Fonts/msyh.ttc", "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc");
        assumeTrue(font != null, "Install a CJK TTC font to run the Chinese collection-font regression");
        textAndVector("一口中文文档转换测试", new Font("SimSun", "宋体", font), "chinese");
    }

    private void textAndVector(String text, Font font, String name) throws Exception {
        Path ofd = directory.resolve(name + ".ofd");
        try (OFDDoc doc = new OFDDoc(ofd)) {
            Paragraph paragraph = new Paragraph(text, 8d, font);
            paragraph.setBorder(.5d);
            doc.add(paragraph);
        }
        int textObjects = 0, vectorObjects = 0;
        try (var zip = new java.util.zip.ZipFile(ofd.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().endsWith(".xml")) continue;
                String xml;
                try (var stream = zip.getInputStream(entry)) { xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
                textObjects += xml.split("<ofd:TextObject", -1).length - 1;
                vectorObjects += xml.split("<ofd:PathObject", -1).length - 1;
            }
        }
        assertTrue(textObjects > 0 && vectorObjects > 0, "Fixture must contain actual text and vector objects");
        Path pdfFile = directory.resolve(name + ".pdf");
        assertEquals(1, OfdWorker.toPdf(ofd, pdfFile));
        try (PDDocument pdf = PDDocument.load(pdfFile.toFile(), MemoryUsageSetting.setupTempFileOnly())) {
            assertEquals(1, pdf.getNumberOfPages());
            BufferedImage image = new PDFRenderer(pdf).renderImage(0);
            int ink = 0;
            int minX = image.getWidth(), maxX = 0, minY = image.getHeight(), maxY = 0;
            for (int y = 80; y < 103; y++) {
                for (int x = 95; x < 430; x++) {
                    Color color = new Color(image.getRGB(x, y));
                    if (color.getRed() < 100 && color.getGreen() < 100 && color.getBlue() < 100) {
                        ink++;
                        minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                        minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                    }
                }
            }
            assertTrue(ink > 300, "Text region must contain visible glyphs rather than just the outside vector border");
            assertTrue(maxX - minX > 100 && maxY - minY > 8, "Glyph bounding box must retain text line width and height");
        }
    }

    private Path firstInstalled(String... candidates) {
        for (String candidate : candidates) if (Files.isRegularFile(Path.of(candidate))) return Path.of(candidate);
        return null;
    }

    private Path archive(String name, String xml) throws Exception {
        Path file = Files.createTempFile(directory, "unsafe-", ".ofd");
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            if (!"OFD.xml".equals(name)) {
                zip.putNextEntry(new ZipEntry("OFD.xml"));
                zip.write("<OFD/>".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry(name));
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return file;
    }
}
