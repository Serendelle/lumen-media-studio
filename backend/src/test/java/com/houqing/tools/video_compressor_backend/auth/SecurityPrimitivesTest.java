package com.houqing.tools.video_compressor_backend.auth;

import com.houqing.tools.video_compressor_backend.support.TestMedia;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityPrimitivesTest {
    private final JwtService jwt = new JwtService(TestMedia.props(Path.of("target", "unused")));
    private final DownloadTicketService tickets = new DownloadTicketService(jwt);

    @Test
    void ticketValidOnlyForItsFileAndUntilExpiry() {
        DownloadTicketService.Ticket t = tickets.issue(7, "file-a");
        assertThat(tickets.verify(t.value(), "file-a")).contains(7L);
        assertThat(tickets.verify(t.value(), "file-b")).isEmpty();

        DownloadTicketService.Ticket expired = tickets.issue(7, "file-a", System.currentTimeMillis() - 1);
        assertThat(tickets.verify(expired.value(), "file-a")).isEmpty();
    }

    @Test
    void ticketFromAnotherSecretIsRejected() {
        var other = new DownloadTicketService(new JwtService(new com.houqing.tools.video_compressor_backend.config.AppProperties(
                "x", 24, null, null, new com.houqing.tools.video_compressor_backend.config.AppProperties.Security("different", 1, true), null, null)));
        assertThat(tickets.verify(other.issue(7, "file-a").value(), "file-a")).isEmpty();
    }

    @Property(tries = 500)
    void tamperedTicketsNeverVerify(@ForAll @AlphaChars @StringLength(min = 1, max = 3) String junk) {
        String good = tickets.issue(1, "file-a").value();
        int dot = good.indexOf('.');
        // flip the user id inside the payload but keep the original signature
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("file-a|999|" + (System.currentTimeMillis() + 60000)).getBytes());
        assertThat(tickets.verify(forgedPayload + good.substring(dot), "file-a")).isEmpty();
        assertThat(tickets.verify(good + junk, "file-a")).isEmpty();
        assertThat(tickets.verify(junk + good, "file-a")).isEmpty();
    }

    @Test
    void garbageTicketsAreRejectedNotThrown() {
        for (String s : new String[]{"", ".", "a.", ".b", "%%%.%%%", "a.b.c", null}) {
            assertThat(tickets.verify(s, "file-a")).isEmpty();
        }
    }

    @Test
    void loginLimiterBlocksAfterMaxFailuresAndResets() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        String key = "1.2.3.4|alice";
        for (int i = 0; i < LoginRateLimiter.MAX_FAILURES; i++) {
            assertThat(limiter.isBlocked(key)).isFalse();
            limiter.recordFailure(key);
        }
        assertThat(limiter.isBlocked(key)).isTrue();
        assertThat(limiter.isBlocked("1.2.3.4|bob")).isFalse();
        limiter.reset(key);
        assertThat(limiter.isBlocked(key)).isFalse();
    }

    @Test
    void jwtRoundTripAndIssuerCheck() {
        UserAccount u = new UserAccount();
        u.setId(42L);
        u.setUsername("alice");
        String token = jwt.issue(u);
        assertThat(jwt.decoder().decode(token).getSubject()).isEqualTo("42");
    }
}
