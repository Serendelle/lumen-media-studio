package org.ofdrw.converter;

import org.ofdrw.converter.font.FontWrapper;
import org.ofdrw.converter.font.MemoryTTFDataStream;
import org.ofdrw.converter.font.TrueTypeCollection;
import org.ofdrw.converter.font.TrueTypeFont;
import org.ofdrw.core.text.font.CT_Font;
import org.ofdrw.reader.ResourceLocator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Local font loading for the isolated AWT renderer, without iText's PDF font API.
 *
 * Upstream OFDRW 2.4.0 FontLoader mixes the AWT loadFontSimilar entry point with iText
 * methods/fields. Even its AWT entry point cannot be linked without iText. This adapter
 * uses OFDRW's actual TrueType/OpenType/TTC parsers for embedded fonts and trusted OS
 * font directories. It intentionally exposes only the API used by AWTMaker.
 * Reference: Apache-2.0 OFDRW 2.4.0 converter/FontLoader.java.
 */
public final class FontLoader {
    private static final FontLoader INSTANCE = new FontLoader();
    private static final Map<String, String> ALIASES = Map.of("宋体", "simsun", "黑体", "simhei", "楷体", "simkai", "仿宋", "simfang");
    private static final List<Path> ROOTS = List.of(Path.of("C:/Windows/Fonts"), Path.of("/usr/share/fonts"),
            Path.of("/usr/local/share/fonts"), Path.of("/System/Library/Fonts"), Path.of("/Library/Fonts"));
    private List<Path> systemFonts;

    private FontLoader() {}
    public static FontLoader getInstance() { return INSTANCE; }

    public FontWrapper<TrueTypeFont> loadFontSimilar(ResourceLocator locator, CT_Font description) {
        if (description == null) throw new IllegalArgumentException("Missing font description");
        String family = description.getFamilyName(), name = description.getFontName();
        if (description.getFontFile() != null) {
            try {
                Path embedded = locator.getFile(description.getFontFile());
                return new FontWrapper<>(parse(embedded, family, name), false);
            } catch (Exception ignored) {
                // A missing/corrupt embedded font may still have an exact installed equivalent.
            }
        }
        Path selected = systemFont(family, name);
        if (selected != null) {
            try { return new FontWrapper<>(parse(selected, family, name), true); }
            catch (IOException ignored) { /* Try a known local fallback below. */ }
        }
        for (Path fallback : List.of(Path.of("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"),
                Path.of("C:/Windows/Fonts/simsun.ttc"), Path.of("C:/Windows/Fonts/arial.ttf"),
                Path.of("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
                Path.of("/System/Library/Fonts/Supplemental/Arial.ttf"))) {
            if (!Files.isRegularFile(fallback)) continue;
            try { return new FontWrapper<>(parse(fallback, family, name), true); }
            catch (IOException ignored) { /* Try the next trusted installed font. */ }
        }
        throw new IllegalArgumentException("No usable embedded or installed font");
    }

    private static TrueTypeFont parse(Path file, String family, String name) throws IOException {
        long size = Files.size(file);
        if (size < 4 || size > 32L * 1024 * 1024) throw new IOException("Font size limit");
        byte[] bytes = Files.readAllBytes(file);
        if (bytes[0] == 't' && bytes[1] == 't' && bytes[2] == 'c' && bytes[3] == 'f') {
            TrueTypeCollection collection = new TrueTypeCollection().parse(new MemoryTTFDataStream(bytes));
            if (collection.getNumFonts() < 1 || collection.getNumFonts() > 100) throw new IOException("Font collection limit");
            TrueTypeFont selected = collection.getFontAtIndex(0);
            for (int i = 0; i < collection.getNumFonts(); i++) {
                TrueTypeFont candidate = collection.getFontAtIndex(i);
                if (equal(candidate.psName, name) || equal(candidate.fontFamily, name) || equal(candidate.psName, family)) return candidate;
                if (candidate.psName != null && candidate.psName.toLowerCase(Locale.ROOT).contains("cjksc")) selected = candidate;
            }
            return selected;
        }
        return new TrueTypeFont().parse(bytes);
    }

    private Path systemFont(String family, String name) {
        List<String> desired = new ArrayList<>();
        for (String value : new String[]{name, family}) {
            if (value == null || value.isBlank()) continue;
            desired.add(normalize(value));
            if (ALIASES.containsKey(value)) desired.add(ALIASES.get(value));
        }
        for (Path font : fonts()) {
            String filename = font.getFileName().toString();
            String stem = normalize(filename.substring(0, filename.lastIndexOf('.')));
            if (desired.contains(stem)) return font;
        }
        // Match metadata only after inexpensive file-name matching, and only in trusted OS roots.
        for (Path font : fonts()) {
            try {
                for (java.awt.Font installed : java.awt.Font.createFonts(font.toFile())) {
                    if (desired.contains(normalize(installed.getPSName())) || desired.contains(normalize(installed.getFontName(Locale.ROOT)))
                            || desired.contains(normalize(installed.getFamily(Locale.ROOT)))) return font;
                }
            } catch (Exception ignored) { /* Unsupported local fonts are not candidates. */ }
        }
        return null;
    }

    private synchronized List<Path> fonts() {
        if (systemFonts != null) return systemFonts;
        List<Path> fonts = new ArrayList<>();
        for (Path root : ROOTS) {
            if (!Files.isDirectory(root)) continue;
            try (var files = Files.walk(root, 6)) {
                files.filter(Files::isRegularFile).filter(path -> {
                    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                    return name.endsWith(".ttf") || name.endsWith(".otf") || name.endsWith(".ttc");
                }).limit(2000 - fonts.size()).forEach(fonts::add);
            } catch (IOException ignored) { /* Other trusted roots can still provide fonts. */ }
            if (fonts.size() >= 2000) break;
        }
        systemFonts = List.copyOf(fonts);
        return systemFonts;
    }

    private static String normalize(String value) { return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", ""); }
    private static boolean equal(String left, String right) { return left != null && right != null && left.equalsIgnoreCase(right); }
}
