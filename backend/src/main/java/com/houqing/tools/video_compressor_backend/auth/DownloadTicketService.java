package com.houqing.tools.video_compressor_backend.auth;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;

/**
 * Short-lived, HMAC-signed tickets that let a plain &lt;video src&gt; / download link fetch one file without
 * an Authorization header. A ticket is bound to (fileId, userId, expiry) and cannot be reused for another file.
 */
@Service
public class DownloadTicketService {
    static final long TTL_MS = 10 * 60 * 1000L;
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    private final byte[] key;

    public DownloadTicketService(JwtService jwt) {
        // derive a purpose-specific key so a ticket signature can never be confused with a JWT signature
        this.key = hmac(jwt.key().getEncoded(), "download-ticket-v1".getBytes(StandardCharsets.UTF_8));
    }

    public record Ticket(String value, long expiresAt) {}

    public Ticket issue(long userId, String fileId) {
        long exp = System.currentTimeMillis() + TTL_MS;
        String payload = fileId + "|" + userId + "|" + exp;
        String sig = ENC.encodeToString(hmac(key, payload.getBytes(StandardCharsets.UTF_8)));
        return new Ticket(ENC.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + sig, exp);
    }

    /** Returns the user id if the ticket is authentic, unexpired and issued for exactly this file. */
    public Optional<Long> verify(String ticket, String fileId) {
        if (ticket == null || fileId == null) return Optional.empty();
        int dot = ticket.indexOf('.');
        if (dot <= 0 || dot == ticket.length() - 1) return Optional.empty();
        try {
            byte[] payloadBytes = DEC.decode(ticket.substring(0, dot));
            byte[] sig = DEC.decode(ticket.substring(dot + 1));
            if (!MessageDigest.isEqual(sig, hmac(key, payloadBytes))) return Optional.empty();
            String[] parts = new String(payloadBytes, StandardCharsets.UTF_8).split("\\|");
            if (parts.length != 3 || !parts[0].equals(fileId)) return Optional.empty();
            if (Long.parseLong(parts[2]) < System.currentTimeMillis()) return Optional.empty();
            return Optional.of(Long.parseLong(parts[1]));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static byte[] hmac(byte[] k, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(k, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
