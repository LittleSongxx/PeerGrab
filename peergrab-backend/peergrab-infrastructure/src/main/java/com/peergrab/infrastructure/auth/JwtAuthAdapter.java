package com.peergrab.infrastructure.auth;

import com.peergrab.domain.auth.ports.RefreshTokenPort;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JWT 鉴权真值存于 MySQL；Redis 故障不会阻断鉴权或令牌轮换。
 * refresh token 是 256 位随机凭证，数据库只保存 SHA-256 哈希。
 */
@Component
@ConditionalOnProperty(name = "peergrab.auth.mode", havingValue = "jwt")
public class JwtAuthAdapter implements RefreshTokenPort {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;
    private final JdbcTemplate jdbc;
    private final long accessTtlMinutes;
    private final long refreshTtlMinutes;

    public JwtAuthAdapter(JdbcTemplate jdbc,
                          @Value("${peergrab.auth.jwt-secret:}") String secret,
                          @Value("${peergrab.auth.session-ttl-minutes:120}") long accessTtlMinutes,
                          @Value("${peergrab.auth.refresh-ttl-minutes:10080}") long refreshTtlMinutes) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT 模式须配置至少 32 字节的 peergrab.auth.jwt-secret");
        }
        if (accessTtlMinutes <= 0 || refreshTtlMinutes < accessTtlMinutes) {
            throw new IllegalArgumentException("JWT 有效期须为正，refresh 有效期须不小于 access 有效期");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.jdbc = jdbc;
        this.accessTtlMinutes = accessTtlMinutes;
        this.refreshTtlMinutes = refreshTtlMinutes;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String login(long userId) {
        String sessionId = createSession(userId, accessTtlMinutes);
        return issueAccessToken(userId, sessionId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TokenPair loginWithRefresh(long userId) {
        String sessionId = createSession(userId, refreshTtlMinutes);
        return new TokenPair(issueAccessToken(userId, sessionId), insertRefreshToken(sessionId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Optional<TokenPair> refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) return Optional.empty();
        String hash = tokenHash(refreshToken);
        String sessionId = findSessionId(hash).orElse(null);
        if (sessionId == null) return Optional.empty();

        // 所有轮换和登出先锁 session，再修改 refresh 行；同一凭证只能兑换一次。
        Session session = lockSession(sessionId).orElse(null);
        if (session == null || !session.active()) return Optional.empty();
        int consumed = jdbc.update("""
                DELETE FROM auth_refresh_token
                 WHERE token_hash = ? AND session_id = ? AND expires_at > NOW(3)
                """, hash, sessionId);
        if (consumed == 0) return Optional.empty();

        jdbc.update("""
                UPDATE auth_token_session
                   SET expires_at = DATE_ADD(NOW(3), INTERVAL ? SECOND)
                 WHERE session_id = ?
                """, seconds(refreshTtlMinutes), sessionId);
        String replacement = insertRefreshToken(sessionId);
        return Optional.of(new TokenPair(issueAccessToken(session.userId(), sessionId), replacement));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean revokeRefresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) return false;
        String hash = tokenHash(refreshToken);
        String sessionId = findSessionId(hash).orElse(null);
        if (sessionId == null) return false;
        Session session = lockSession(sessionId).orElse(null);
        if (session == null || !session.active()) return false;
        int deleted = jdbc.update("""
                DELETE FROM auth_refresh_token
                 WHERE token_hash = ? AND session_id = ? AND expires_at > NOW(3)
                """, hash, sessionId);
        if (deleted == 0) return false;
        revokeSession(sessionId);
        return true;
    }

    @Override
    public Optional<Long> resolve(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        try {
            Claims claims = parse(token).getPayload();
            String sessionId = claims.get("sid", String.class);
            long userId = Long.parseLong(claims.getSubject());
            if (sessionId == null) return Optional.empty();
            Integer valid = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM auth_token_session s
                     WHERE s.session_id = ? AND s.user_id = ?
                       AND s.revoked_at IS NULL AND s.expires_at > NOW(3)
                    """, Integer.class, sessionId, userId);
            return valid != null && valid == 1 ? Optional.of(userId) : Optional.empty();
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void logout(String token) {
        if (token == null || token.isBlank()) return;
        try {
            Claims claims = parse(token).getPayload();
            String sessionId = claims.get("sid", String.class);
            long userId = Long.parseLong(claims.getSubject());
            if (sessionId == null) return;
            jdbc.update("""
                    UPDATE auth_token_session SET revoked_at = COALESCE(revoked_at, NOW(3))
                     WHERE session_id = ? AND user_id = ?
                    """, sessionId, userId);
            jdbc.update("DELETE FROM auth_refresh_token WHERE session_id = ?", sessionId);
        } catch (JwtException | IllegalArgumentException ignored) {
            // 已过期、格式错误或签名无效的 token 无需撤销。
        }
    }

    private String createSession(long userId, long ttlMinutes) {
        String sessionId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO auth_token_session (session_id, user_id, expires_at)
                VALUES (?, ?, DATE_ADD(NOW(3), INTERVAL ? SECOND))
                """, sessionId, userId, seconds(ttlMinutes));
        return sessionId;
    }

    private String insertRefreshToken(String sessionId) {
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        jdbc.update("""
                INSERT INTO auth_refresh_token (token_hash, session_id, expires_at)
                VALUES (?, ?, DATE_ADD(NOW(3), INTERVAL ? SECOND))
                """, tokenHash(token), sessionId, seconds(refreshTtlMinutes));
        return token;
    }

    private String issueAccessToken(long userId, String sessionId) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                // 同一秒内轮换 access token 仍需唯一，jti 不参与服务端撤销判定。
                .id(UUID.randomUUID().toString())
                .claim("sid", sessionId)
                .issuedAt(now)
                .expiration(new Date(Math.addExact(now.getTime(),
                        Math.multiplyExact(accessTtlMinutes, 60_000))))
                .signWith(key)
                .compact();
    }

    private Jws<Claims> parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
    }

    private Optional<String> findSessionId(String hash) {
        List<String> sessions = jdbc.queryForList("""
                SELECT session_id FROM auth_refresh_token
                 WHERE token_hash = ? AND expires_at > NOW(3)
                """, String.class, hash);
        return sessions.stream().findFirst();
    }

    private Optional<Session> lockSession(String sessionId) {
        List<Session> rows = jdbc.query("""
                SELECT user_id, revoked_at, expires_at > NOW(3) AS unexpired
                  FROM auth_token_session WHERE session_id = ? FOR UPDATE
                """, (rs, rowNum) -> new Session(rs.getLong("user_id"),
                rs.getTimestamp("revoked_at") == null && rs.getBoolean("unexpired")), sessionId);
        return rows.stream().findFirst();
    }

    private void revokeSession(String sessionId) {
        jdbc.update("""
                UPDATE auth_token_session SET revoked_at = COALESCE(revoked_at, NOW(3))
                 WHERE session_id = ?
                """, sessionId);
        jdbc.update("DELETE FROM auth_refresh_token WHERE session_id = ?", sessionId);
    }

    private static String tokenHash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境缺少 SHA-256", e);
        }
    }

    private static long seconds(long minutes) {
        return Math.multiplyExact(minutes, 60);
    }

    private record Session(long userId, boolean active) {}
}
