package com.jobbuddy.backend.modules.auth.service;

import com.jobbuddy.backend.modules.auth.repository.UserAuthRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class UserLoginService {
    private final UserAuthRepository repository;
    private final SecureRandom secureRandom = new SecureRandom();
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UserLoginService(UserAuthRepository repository) {
        this.repository = repository;
    }

    public Map<String, Object> login(String username, String password) {
        String safeUsername = username == null ? "" : username.trim();
        String safePassword = password == null ? "" : password;
        if (safeUsername.isEmpty() || safePassword.isEmpty()) {
            throw new IllegalArgumentException("请输入用户名和密码");
        }
        Map<String, Object> user = repository.findUserByUsername(safeUsername);
        if (user == null || !Boolean.TRUE.equals(user.get("enabled"))) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        String passwordHash = String.valueOf(user.get("passwordHash"));
        if (!verifyPassword(safePassword, passwordHash)) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        upgradeLegacyHashIfNeeded(String.valueOf(user.get("userId")), safePassword, passwordHash);
        repository.deleteExpiredSessions();
        String token = newToken();
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);
        repository.saveSession(token, String.valueOf(user.get("userId")), expiresAt);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("token", token);
        result.put("expiresAt", expiresAt.toString());
        result.put("user", publicUser(user));
        return result;
    }

    public Map<String, Object> currentUser(String token) {
        if (token == null || token.trim().isEmpty()) return null;
        repository.deleteExpiredSessions();
        Map<String, Object> user = repository.findUserByToken(token.trim());
        if (user == null || !Boolean.TRUE.equals(user.get("enabled"))) return null;
        Object expiresAt = user.get("expiresAt");
        Instant expiresAtInstant = toInstant(expiresAt);
        if (expiresAtInstant != null && expiresAtInstant.isBefore(Instant.now())) return null;
        repository.touchSession(token.trim());
        return publicUser(user);
    }

    public void logout(String token) {
        if (token != null && !token.trim().isEmpty()) repository.deleteSession(token.trim());
    }

    private Map<String, Object> publicUser(Map<String, Object> user) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("userId", user.get("userId"));
        result.put("username", user.get("username"));
        result.put("displayName", user.get("displayName"));
        result.put("role", user.get("role"));
        return result;
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        StringBuilder builder = new StringBuilder();
        for (byte b : bytes) builder.append(String.format("%02x", b & 0xff));
        return builder.toString();
    }

    /**
     * 校验密码：优先按 BCrypt 校验；存量账号仍是 64 位 SHA-256 摘要时回退到旧算法做常量时间比较，
     * 保证升级 BCrypt 后历史用户仍可登录。
     */
    private boolean verifyPassword(String rawPassword, String storedHash) {
        if (storedHash == null) return false;
        if (isLegacySha256(storedHash)) {
            return constantTimeEquals(storedHash, sha256(rawPassword));
        }
        return passwordEncoder.matches(rawPassword, storedHash);
    }

    /** 旧 SHA-256 账号登录成功后，立即用 BCrypt 重写存储，实现登录即升级，避免长期保留弱摘要。 */
    private void upgradeLegacyHashIfNeeded(String userId, String rawPassword, String storedHash) {
        if (userId == null || userId.isEmpty() || !isLegacySha256(storedHash)) {
            return;
        }
        repository.updatePasswordHash(userId, passwordEncoder.encode(rawPassword));
    }

    private boolean isLegacySha256(String hash) {
        if (hash == null || hash.length() != 64) return false;
        for (int i = 0; i < hash.length(); i++) {
            char c = hash.charAt(i);
            boolean isHex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!isHex) return false;
        }
        return true;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) builder.append(String.format("%02x", b & 0xff));
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("密码摘要计算失败", e);
        }
    }

    private Instant toInstant(Object value) {
        if (value instanceof Instant) return (Instant) value;
        if (value instanceof java.sql.Timestamp) return ((java.sql.Timestamp) value).toInstant();
        if (value instanceof java.util.Date) return ((java.util.Date) value).toInstant();
        if (value == null) return null;
        try {
            return Instant.parse(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) return false;
        byte[] a = left.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.getBytes(StandardCharsets.UTF_8);
        int diff = a.length ^ b.length;
        for (int i = 0; i < Math.min(a.length, b.length); i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }
}
