package com.peergrab.presentation.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Map;

/** Explicitly enabled credentials for the local demo identities. */
@Component
public class DemoLoginPolicy {

    private static final Logger log = LoggerFactory.getLogger(DemoLoginPolicy.class);

    private final boolean enabled;
    private final Map<Long, byte[]> passwords;

    public DemoLoginPolicy(@Value("${peergrab.auth.demo.enabled:false}") boolean enabled,
                           @Value("${peergrab.auth.demo.password-1001:}") String password1001,
                           @Value("${peergrab.auth.demo.password-2001:}") String password2001,
                           @Value("${peergrab.auth.demo.password-2002:}") String password2002,
                           @Value("${peergrab.auth.demo.password-9001:}") String password9001,
                           @Value("${peergrab.auth.arbitrator-id:9001}") long arbitratorId) {
        this.enabled = enabled;
        if (enabled && arbitratorId != 9001L) {
            throw new IllegalArgumentException("演示身份 9001 必须与 peergrab.auth.arbitrator-id 一致");
        }
        this.passwords = Map.of(
                1001L, password1001.getBytes(StandardCharsets.UTF_8),
                2001L, password2001.getBytes(StandardCharsets.UTF_8),
                2002L, password2002.getBytes(StandardCharsets.UTF_8),
                9001L, password9001.getBytes(StandardCharsets.UTF_8));
        if (enabled && (passwords.values().stream().anyMatch(p -> p.length < 8)
                || new HashSet<>(java.util.List.of(password1001, password2001, password2002, password9001)).size() != 4)) {
            throw new IllegalArgumentException("演示登录启用时，四个身份须各配置不同的至少 8 字符密码");
        }
        if (enabled) {
            log.warn("演示登录已启用：四个固定演示身份（含仲裁员 {}）可用口令登录。"
                    + "该功能仅用于公开演示；真实业务部署必须关闭并接入正式身份系统。", arbitratorId);
        }
    }

    public boolean allows(Long userId, String password) {
        if (!enabled || userId == null || password == null) {
            return false;
        }
        byte[] expected = passwords.get(userId);
        return expected != null && MessageDigest.isEqual(expected, password.getBytes(StandardCharsets.UTF_8));
    }
}
