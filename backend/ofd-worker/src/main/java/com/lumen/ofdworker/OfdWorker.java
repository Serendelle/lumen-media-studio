package com.lumen.ofdworker;

import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.ofdrw.converter.ImageMaker;
import org.ofdrw.layout.OFDDoc;
import org.ofdrw.layout.PageLayout;
import org.ofdrw.layout.VirtualPage;
import org.ofdrw.layout.element.Img;
import org.ofdrw.layout.element.Position;
import org.ofdrw.reader.OFDReader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.ArrayList;
import java.util.Properties;

/** A separate JVM keeps OFDRW's PDFBox 2 implementation away from the PDFBox 3 application. */
public final class OfdWorker {
    private static final int MAX_PAGES = 200;
    private static final long MAX_PIXELS = 16_000_000;
    private OfdWorker() {}

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        PrintStream diagnostics = System.err;
        RenderDiagnostics rendering = new RenderDiagnostics();
        // Library failures sometimes log and continue with missing objects. Track those failures,
        // and do not let third-party diagnostics expose document contents or internal paths.
        System.setErr(new PrintStream(rendering, true, StandardCharsets.UTF_8));
        try {
            if (args.length != 3) throw new IllegalArgumentException("Invalid operation");
            int pages = switch (args[0]) {
                case "to-pdf" -> toPdf(Path.of(args[1]), Path.of(args[2]));
                case "from-pages" -> fromPages(Path.of(args[1]), Path.of(args[2]));
                default -> throw new IllegalArgumentException("Invalid operation");
            };
            if (rendering.failed) throw new IOException("Incomplete rendering");
            System.out.println("OFD_OK pages=" + pages);
        } catch (Exception | LinkageError e) {
            // Tool tails are logged by the application; never expose document text, paths or credentials.
            diagnostics.println("OFD_FAILED " + e.getClass().getSimpleName());
            System.exit(2);
        }
    }

    private static final class RenderDiagnostics extends OutputStream {
        private final StringBuilder line = new StringBuilder();
        private volatile boolean failed;

        @Override public synchronized void write(int value) {
            if (value == '\n') {
                String text = line.toString();
                if (text.contains("org.ofdrw.") && (text.contains(" WARN ") || text.contains(" ERROR "))) failed = true;
                line.setLength(0);
            } else if (line.length() < 4096) {
                line.append((char) value);
            }
        }
    }

    static int toPdf(Path input, Path output) throws Exception {
        Path extracted = Files.createTempDirectory("lumen-ofd-read-");
        try {
            OfdArchive.extract(input, extracted);
            int count;
            try (OFDReader reader = new OFDReader(extracted.toString(), false);
                 PDDocument pdf = new PDDocument(MemoryUsageSetting.setupTempFileOnly())) {
                count = reader.getNumberOfPages();
                if (count < 1 || count > MAX_PAGES) throw new IOException("Page limit");
                for (int i = 0; i < count; i++) {
                    var box = reader.getPageInfo(i + 1).getSize();
                    double width = box.getWidth(), height = box.getHeight();
                    dimensions(width, height);
                    double ppm = Math.min(150d / 25.4, Math.sqrt(MAX_PIXELS / (width * height)));
                    BufferedImage image = new ImageMaker(reader, ppm).makePage(i);
                    if (image == null || (long) image.getWidth() * image.getHeight() > MAX_PIXELS + 20_000) throw new IOException("Invalid rendering");
                    PDPage page = new PDPage(new PDRectangle((float) (width * 72 / 25.4), (float) (height * 72 / 25.4)));
                    pdf.addPage(page);
                    var embedded = LosslessFactory.createFromImage(pdf, image);
                    try (PDPageContentStream stream = new PDPageContentStream(pdf, page)) {
                        stream.drawImage(embedded, 0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
                    }
                    image.flush();
                }
                pdf.save(output.toFile());
            }
            try (PDDocument check = PDDocument.load(output.toFile(), MemoryUsageSetting.setupTempFileOnly())) {
                if (check.getNumberOfPages() != count) throw new IOException("Output page mismatch");
            }
            return count;
        } finally {
            deleteTree(extracted);
        }
    }

    static int fromPages(Path manifest, Path output) throws Exception {
        Properties properties = new Properties();
        try (var in = Files.newInputStream(manifest)) { properties.load(in); }
        int count = Integer.parseInt(properties.getProperty("count", "0"));
        if (count < 1 || count > MAX_PAGES) throw new IOException("Page limit");
        List<double[]> dimensions = new ArrayList<>();
        try (OFDDoc doc = new OFDDoc(output)) {
            for (int i = 0; i < count; i++) {
                Path file = Path.of(properties.getProperty("page." + i + ".file"));
                double width = Double.parseDouble(properties.getProperty("page." + i + ".width"));
                double height = Double.parseDouble(properties.getProperty("page." + i + ".height"));
                dimensions(width, height);
                dimensions.add(new double[]{width, height});
                BufferedImage image = checkedImage(file);
                Path normalized = manifest.getParent().resolve("normalized-" + i + ".png");
                if (!ImageIO.write(image, "png", normalized.toFile())) throw new IOException("Image encoder");
                image.flush();
                PageLayout layout = new PageLayout(width, height).setMargin(0d);
                VirtualPage page = new VirtualPage(layout);
                Img element = new Img(width, height, normalized);
                element.setPosition(Position.Absolute);
                element.setXY(0d, 0d);
                page.add(element);
                doc.addVPage(page);
            }
        }
        Path extracted = Files.createTempDirectory("lumen-ofd-check-");
        try {
            OfdArchive.extract(output, extracted);
            try (OFDReader check = new OFDReader(extracted.toString(), false)) {
                if (check.getNumberOfPages() != count) throw new IOException("Output page mismatch");
                for (int i = 0; i < count; i++) {
                    var box = check.getPageInfo(i + 1).getSize();
                    if (Math.abs(box.getWidth() - dimensions.get(i)[0]) > .05 || Math.abs(box.getHeight() - dimensions.get(i)[1]) > .05) throw new IOException("Output dimensions mismatch");
                    // A real reader must be able to render every generated page, not merely list its ZIP members.
                    BufferedImage preview = new ImageMaker(check, 0.5d).makePage(i);
                    if (preview == null) throw new IOException("Output rendering failed");
                    preview.flush();
                }
            }
        } finally {
            deleteTree(extracted);
        }
        return count;
    }

    private static BufferedImage checkedImage(Path file) throws IOException {
        try (var stream = ImageIO.createImageInputStream(file.toFile())) {
            if (stream == null) throw new IOException("Invalid image");
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("Unsupported image");
            var reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > MAX_PIXELS) throw new IOException("Image size limit");
                return reader.read(0);
            } finally { reader.dispose(); }
        }
    }

    private static void dimensions(double width, double height) throws IOException {
        if (!Double.isFinite(width) || !Double.isFinite(height) || width < 1 || height < 1 || width > 2000 || height > 2000) throw new IOException("Page dimensions limit");
    }

    private static void deleteTree(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
