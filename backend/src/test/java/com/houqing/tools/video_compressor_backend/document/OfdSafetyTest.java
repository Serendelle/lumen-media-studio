package com.houqing.tools.video_compressor_backend.document;

import com.houqing.tools.video_compressor_backend.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class OfdSafetyTest {
    @TempDir Path directory;

    @Test
    void recognizesRealPackageAndChecksPageReference() throws Exception {
        assertEquals(1, OfdSafety.validate(archive(valid())));
        Map<String, String> missingPage = valid();
        missingPage.remove("Doc_0/Pages/Page_0/Content.xml");
        assertThrows(ApiException.class, () -> OfdSafety.validate(archive(missingPage)));
    }

    @Test
    void refusesZipSlipAndCaseCollisions() throws Exception {
        Map<String, String> traversal = valid();
        traversal.put("../escape.xml", "<x/>");
        assertThrows(ApiException.class, () -> OfdSafety.validate(archive(traversal)));
        Map<String, String> collision = valid();
        collision.put("ofd.xml", "<x/>");
        assertThrows(ApiException.class, () -> OfdSafety.validate(archive(collision)));
    }

    @Test
    void refusesExternalXmlEntitiesAndNetworkResources() throws Exception {
        Map<String, String> xxe = valid();
        xxe.put("Doc_0/Res.xml", "<!DOCTYPE Res [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><Res>&x;</Res>");
        assertThrows(ApiException.class, () -> OfdSafety.validate(archive(xxe)));
        Map<String, String> network = valid();
        network.put("Doc_0/Res.xml", "<Res><MediaFile>https://example.invalid/a.png</MediaFile></Res>");
        assertThrows(ApiException.class, () -> OfdSafety.validate(archive(network)));
        network.put("Doc_0/Res.xml", "<Res><MediaFile><!--split-->https://example.invalid/a.png</MediaFile></Res>");
        assertThrows(ApiException.class, () -> OfdSafety.validate(archive(network)));
        Map<String, String> escape = valid();
        escape.put("Doc_0/Res.xml", "<Res><FontFile>../../secret.ttf</FontFile></Res>");
        assertThrows(ApiException.class, () -> OfdSafety.validate(archive(escape)));
        escape.put("Doc_0/Res.xml", "<Res xmlns='http://www.ofdspec.org/2016' BaseLoc='..'><FontFile>../secret.ttf</FontFile></Res>");
        assertThrows(ApiException.class, () -> OfdSafety.validate(archive(escape)));
    }

    private Map<String, String> valid() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("OFD.xml", "<OFD xmlns='http://www.ofdspec.org/2016'><DocBody><DocRoot>Doc_0/Document.xml</DocRoot></DocBody></OFD>");
        entries.put("Doc_0/Document.xml", "<Document xmlns='http://www.ofdspec.org/2016'><Pages><Page ID='1' BaseLoc='Pages/Page_0/Content.xml'/></Pages></Document>");
        entries.put("Doc_0/Pages/Page_0/Content.xml", "<Page xmlns='http://www.ofdspec.org/2016'><Area><PhysicalBox>0 0 210 297</PhysicalBox></Area></Page>");
        return entries;
    }

    private Path archive(Map<String, String> entries) throws Exception {
        Path file = Files.createTempFile(directory, "safety-", ".ofd");
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (var item : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(item.getKey()));
                zip.write(item.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }
}
