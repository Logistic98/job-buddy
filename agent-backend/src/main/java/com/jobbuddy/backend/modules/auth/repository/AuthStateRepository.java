package com.jobbuddy.backend.modules.auth.repository;

import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.auth.mapper.AuthStateMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Repository
public class AuthStateRepository {
    private final AuthStateMapper mapper;
    private final JsonCodec jsonCodec;

    public AuthStateRepository(AuthStateMapper mapper, JsonCodec jsonCodec) {
        this.mapper = mapper;
        this.jsonCodec = jsonCodec;
    }

    public Map<String, Object> findByProvider(String provider) {
        Map<String, Object> row = mapper.findByProvider(provider);
        if (row == null) return null;
        Map<String, Object> result = new LinkedHashMap<String, Object>(row);
        result.put("metadata", jsonCodec.toMap(string(row.get("metadataJson"))));
        return result;
    }

    public void save(String provider, String status, String credentialJson, Map<String, Object> metadata) {
        Map<String, Object> row = new HashMap<String, Object>();
        Instant now = Instant.now();
        row.put("provider", provider);
        row.put("status", status);
        row.put("credentialJson", credentialJson);
        row.put("metadataJson", jsonCodec.toJson(metadata));
        row.put("createdAt", now);
        row.put("updatedAt", now);
        if (mapper.countByProvider(provider) > 0) mapper.updateState(row); else mapper.insertState(row);
    }

    public void updateStatus(String provider, String status, Map<String, Object> metadata) {
        Map<String, Object> existing = findByProvider(provider);
        save(provider, status, existing == null ? null : (String) existing.get("credentialJson"), metadata);
    }

    private String string(Object value) { return value == null ? null : String.valueOf(value); }
}
