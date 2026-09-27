package com.peergrab.bench;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** Issues benchmark JWTs only after creating matching sessions in a verified disposable database. */
final class BenchJwtTokens {

    private final byte[] secret;
    private final String algorithm;
    private final String header;
    private final Connection db;

    BenchJwtTokens(String secret, Connection db) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("PEERGRAB_AUTH_JWT_SECRET must contain at least 32 UTF-8 bytes");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.db = db;
        verifyDisposableDatabase();
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
        if (userId <= 0) throw new IllegalArgumentException("Benchmark userId must be positive");
        long now = Instant.now().getEpochSecond();
        String sessionId = UUID.randomUUID().toString();
        try (PreparedStatement statement = db.prepareStatement("""
                INSERT INTO auth_token_session (session_id, user_id, expires_at)
                VALUES (?, ?, DATE_ADD(NOW(3), INTERVAL 7200 SECOND))
                """)) {
            statement.setString(1, sessionId);
            statement.setLong(2, userId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot persist benchmark auth session", e);
        }
        String payload = "{\"sub\":\"" + userId + "\",\"sid\":\"" + sessionId
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

    private void verifyDisposableDatabase() {
        String project = System.getenv("PEERGRAB_BENCH_PROJECT");
        if (!"YES".equals(System.getenv("PEERGRAB_BENCH_DISPOSABLE"))
                || project == null || !project.matches("peergrab-bench-[a-z0-9][a-z0-9_-]*")) {
            throw new IllegalStateException("Benchmark auth fixture requires a disposable bench project");
        }
        try (PreparedStatement statement = db.prepareStatement(
                "SELECT project_name FROM bench_guard WHERE guard_key = 'project'");
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next() || !project.equals(rows.getString(1)) || rows.next()) {
                throw new IllegalStateException("Benchmark database marker differs from runner project");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot verify disposable benchmark database", e);
        }
    }

    private static String base64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
