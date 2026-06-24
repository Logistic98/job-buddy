package com.jobbuddy.backend.modules.auth.repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Repository;

import com.jobbuddy.backend.modules.auth.mapper.UserAuthMapper;

/**
 * Repository adapter that keeps authentication persistence details behind the service layer.
 */
@Repository
public class UserAuthRepository {
    private final UserAuthMapper mapper;

    public UserAuthRepository(UserAuthMapper mapper) {
        this.mapper = mapper;
    }

    public Map<String, Object> findUserByUsername(String username) {
        return normalizeTimeStrings(mapper.findUserByUsername(username), "createdAt", "updatedAt");
    }

    public Map<String, Object> findUserByToken(String token) {
        return normalizeTimeStrings(mapper.findUserByToken(token), "expiresAt");
    }

    public void updatePasswordHash(String userId, String passwordHash) {
        mapper.updatePasswordHash(userId, passwordHash, Instant.now());
    }

    public void saveSession(String token, String userId, Instant expiresAt) {
        mapper.saveSession(token, userId, expiresAt, Instant.now());
    }

    public void touchSession(String token) {
        mapper.touchSession(token, Instant.now());
    }

    public void deleteSession(String token) {
        mapper.deleteSession(token);
    }

    public void deleteExpiredSessions() {
        mapper.deleteExpiredSessions(Instant.now());
    }

    private Map<String, Object> normalizeTimeStrings(Map<String, Object> row, String... keys) {
        if (row == null) {
            return null;
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>(row);
        for (String key : keys) {
            if (result.get(key) != null) {
                result.put(key, toInstant(result.get(key)).toString());
            }
        }
        return result;
    }

    private Instant toInstant(Object value) {
        if (value instanceof Instant) {
            return (Instant) value;
        }
        if (value instanceof java.sql.Timestamp) {
            return ((java.sql.Timestamp) value).toInstant();
        }
        if (value instanceof java.util.Date) {
            return ((java.util.Date) value).toInstant();
        }
        return Instant.parse(String.valueOf(value));
    }
}
