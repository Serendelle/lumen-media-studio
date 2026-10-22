package com.houqing.tools.video_compressor_backend.collaboration;

import com.houqing.tools.video_compressor_backend.common.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TencentDocsLinkProviderTests {
    private final TencentDocsLinkProvider provider = new TencentDocsLinkProvider();

    @Test void acceptsOnlyDirectOfficialDocumentLinksAndPreservesSheetTab() {
        assertEquals("TENCENT_DOCS_LINK", provider.id());
        DocumentProvider.Link word = provider.validate("https://docs.qq.com/doc/DVGVzdF9kb2Mx");
        assertEquals("DOCUMENT", word.documentType());
        assertEquals("https://docs.qq.com/doc/DVGVzdF9kb2Mx", word.url());
        DocumentProvider.Link sheet = provider.validate("HTTPS://DOCS.QQ.COM/sheet/DTmV3X3NoZWV0?tab=BB08J2&u=12345#A1");
        assertEquals("SPREADSHEET", sheet.documentType());
        assertEquals("https://docs.qq.com/sheet/DTmV3X3NoZWV0?tab=BB08J2&u=12345#A1", sheet.url());
        assertEquals("SLIDE", provider.validate("https://docs.qq.com/slide/DU2xpZGVfaWQx").documentType());
    }

    @Test void rejectsHostConfusionRedirectsScriptSchemesAndNonDocumentRoutes() {
        List<String> invalid = List.of(
                "http://docs.qq.com/doc/DAAAA", "javascript:alert(1)", "data:text/html,hello",
                "//docs.qq.com/doc/DAAAA", "https://docs.qq.com.evil.test/doc/DAAAA",
                "https://evildocs.qq.com/doc/DAAAA", "https://evil.test/docs.qq.com/doc/DAAAA",
                "https://docs.qq.com@evil.test/doc/DAAAA", "https://evil.test@docs.qq.com/doc/DAAAA",
                "https://docs.qq.com:443/doc/DAAAA", "https://docs.qq.com:8443/doc/DAAAA",
                "https://docs.qq.com./doc/DAAAA", "https://DOCS%2EQQ%2ECOM/doc/DAAAA",
                "https://docs.qq.com%40evil.test/doc/DAAAA", "https://docs.qq.com\\@evil.test/doc/DAAAA",
                "https://docs.qq.com/doc/DAAAA?redirect=https://evil.test", "https://docs.qq.com/doc/DAAAA?url=evil.test",
                "https://docs.qq.com/doc/DAAAA?tab=https%3A%2F%2Fevil.test", "https://docs.qq.com/doc/DAAAA?tab=AA&tab=BB",
                "https://docs.qq.com/doc/DAAAA?tab=AA%26redirect%3Devil", "https://docs.qq.com/open/",
                "https://docs.qq.com/redirect/DAAAA", "https://docs.qq.com/", "https://docs.qq.com/doc/../DAAAA",
                "https://docs.qq.com/doc/%44AAAA", "https://docs.qq.com/doc/DAAAA%2F..%2Fredirect",
                "https://doc.weixin.qq.com/doc/DAAAA", " https://docs.qq.com/doc/DAAAA", "https://docs.qq.com/doc/DAAAA\n",
                "https://docs.qq.com/doc/DAAAA#javascript:alert(1)", "https://docs.qq.com/doc/DAAAA?tab=A;url=evil",
                "https://docs.qq.com/doc/DAAAA?", "https://docs.qq.com/doc/DAAAA?tab=A&&u=1");
        for (String url : invalid) assertThrows(ApiException.class, () -> provider.validate(url), url);
        assertThrows(ApiException.class, () -> provider.validate(null));
        assertThrows(ApiException.class, () -> provider.validate("https://docs.qq.com/doc/" + "A".repeat(2100)));
    }

    @Test void manualExportFormatsAreExplicitAndTypeSpecific() {
        assertTrue(provider.importExtensions("DOCUMENT").contains("docx"));
        assertTrue(provider.importExtensions("SPREADSHEET").contains("xlsx"));
        assertFalse(provider.importExtensions("SPREADSHEET").contains("docx"));
        assertFalse(provider.importExtensions("SLIDE").contains("xlsx"));
        assertFalse(provider.importExtensions("SLIDE").contains("pptx"));
        assertFalse(provider.importExtensions("SPREADSHEET").contains("csv"));
        assertFalse(provider.importExtensions("OTHER").contains("exe"));
        assertThrows(ApiException.class, () -> provider.importExtensions("SCRIPT"));
    }
}
