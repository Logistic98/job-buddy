package com.jobbuddy.backend.modules.prompt.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class UserProfileContext {
    private final Map<String, Object> profile;
    private final String summary;

    public UserProfileContext(Map<String, Object> profile, String summary) {
        this.profile = profile == null ? Collections.<String, Object>emptyMap() : new LinkedHashMap<String, Object>(profile);
        this.summary = summary == null ? "" : summary;
    }

    public Map<String, Object> getProfile() {
        return profile;
    }

    public String getSummary() {
        return summary;
    }

    public boolean isEmpty() {
        return profile.isEmpty() && summary.trim().isEmpty();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("summary", summary);
        data.put("profile", profile);
        return data;
    }
}
