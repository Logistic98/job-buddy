package com.jobbuddy.backend.modules.auth.service;

import com.jobbuddy.backend.modules.auth.repository.UserAuthRepository;
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
        if (!constantTimeEquals(passwordHash, sha256(safePassword))) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
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
        if (expiresAt instanceof Instant && ((Instant) expiresAt).isBefore(Instant.now())) return null;
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

    private boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) return false;
        byte[] a = left.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.getBytes(StandardCharsets.UTF_8);
        int diff = a.length ^ b.length;
        for (int i = 0; i < Math.min(a.length, b.length); i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }
}
