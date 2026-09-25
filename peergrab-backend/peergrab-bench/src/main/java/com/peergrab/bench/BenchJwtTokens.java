package com.peergrab.bench;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** Issues short-lived benchmark identities for an isolated JWT-mode stack. */
final class BenchJwtTokens {

    private final byte[] secret;
    private final String algorithm;
    private final String header;

    BenchJwtTokens(String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("PEERGRAB_AUTH_JWT_SECRET must contain at least 32 UTF-8 bytes");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        if (this.secret.length >= 64) {
            algorithm = "HmacSHA512";
            header = "{\"alg\":\"HS512\",\"typ\":\"JWT\"}";
        } else if (this.secret.length >= 48) {
            algorithm = "HmacSHA384";
            header = "{\"alg\":\"HS384\",\"typ\":\"JWT\"}";
        } else {
            algorithm = "HmacSHA256";
            header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        }
    }

    String issue(long userId) {
        long now = Instant.now().getEpochSecond();
        String payload = "{\"sub\":\"" + userId + "\",\"jti\":\"" + UUID.randomUUID()
                + "\",\"iat\":" + now + ",\"exp\":" + (now + 7200) + "}";
        String signingInput = base64(header.getBytes(StandardCharsets.UTF_8)) + "."
                + base64(payload.getBytes(StandardCharsets.UTF_8));
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(secret, algorithm));
            return signingInput + "." + base64(mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot sign benchmark JWT", e);
        }
    }

    private static String base64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
