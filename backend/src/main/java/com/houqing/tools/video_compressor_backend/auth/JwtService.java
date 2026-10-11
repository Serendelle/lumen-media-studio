package com.houqing.tools.video_compressor_backend.auth;

import com.houqing.tools.video_compressor_backend.config.AppProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** HS256 JWT issuing/verification. Secret comes from APP_JWT_SECRET; a random one is used if absent. */
@Service
public class JwtService {
    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    static final String ISSUER = "lumiere-media";

    private final SecretKey key;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final int ttlHours;

    public JwtService(AppProperties props) {
        this.key = deriveKey(props.security().jwtSecret());
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        NimbusJwtDecoder d = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        d.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
        this.decoder = d;
        this.ttlHours = Math.max(1, props.security().jwtTtlHours());
    }

    private static SecretKey deriveKey(String secret) {
        byte[] material;
        if (secret == null || secret.isBlank()) {
            material = new byte[32];
            new SecureRandom().nextBytes(material);
            log.warn("APP_JWT_SECRET is not set: using a random secret, existing logins will be invalid after restart");
        } else {
            try {
                // hash so that any length of secret gives a valid 256-bit HMAC key
                material = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return new SecretKeySpec(material, "HmacSHA256");
    }

    public String issue(UserAccount user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(String.valueOf(user.getId()))
                .claim("username", user.getUsername())
                .issuedAt(now)
                .expiresAt(now.plus(ttlHours, ChronoUnit.HOURS))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public long ttlSeconds() { return ttlHours * 3600L; }

    public JwtDecoder decoder() { return decoder; }

    /** Raw key for signing short-lived download tickets (same secret, different purpose prefix). */
    SecretKey key() { return key; }
}
