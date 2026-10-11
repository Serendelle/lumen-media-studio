package com.houqing.tools.video_compressor_backend.media;

import net.jqwik.api.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Client-supplied names are only ever used for display; these properties show they cannot carry a path. */
class StorageNameProperties {

    @Property(tries = 5000)
    void sanitizedNameIsAPlainFileName(@ForAll String raw) {
        String s = StorageService.sanitizeName(raw);
        assertThat(s).isNotBlank();
        assertThat(s).doesNotContain("/", "\\", ":", "*", "?", "\"", "<", ">", "|");
        assertThat(s).isNotIn(".", "..");
        assertThat(s.length()).isLessThanOrEqualTo(200);
        assertThat(s.chars().noneMatch(c -> c < 0x20)).isTrue();
    }

    @Property(tries = 2000)
    void traversalSequencesAreNeutralised(@ForAll("traversal") String raw) {
        String s = StorageService.sanitizeName(raw);
        assertThat(s).doesNotContain("/", "\\");
        assertThat(s).isNotEqualTo("..");
    }

    @Provide
    Arbitrary<String> traversal() {
        Arbitrary<String> seg = Arbitraries.of("..", ".", "etc", "Windows", "C:", "%2e%2e", "PWNED.mp4", "a b");
        Arbitrary<String> sep = Arbitraries.of("/", "\\", "\\\\", "//");
        return Combinators.combine(seg.list().ofMinSize(1).ofMaxSize(6), sep)
                .as((parts, sp) -> String.join(sp, parts));
    }

    @Property(tries = 2000)
    void extensionIsShortAlphanumericOrEmpty(@ForAll String raw) {
        String ext = StorageService.extensionOf(raw);
        assertThat(ext).matches("[a-z0-9]{0,8}");
    }

    @Example
    void examples() {
        assertThat(StorageService.sanitizeName("..\\..\\Windows\\win.ini")).isEqualTo("win.ini");
        assertThat(StorageService.sanitizeName("../../PLANTED.txt")).isEqualTo("PLANTED.txt");
        assertThat(StorageService.sanitizeName("演示 视频.mp4")).isEqualTo("演示 视频.mp4");
        assertThat(StorageService.sanitizeName(null)).isEqualTo("file");
        assertThat(StorageService.extensionOf("x\\..\\..\\PWNED.mp4")).isEqualTo("mp4");
        assertThat(StorageService.extensionOf("noext")).isEmpty();
    }
}
