package com.jobbuddy.backend.modules.prompt.service;

import com.jobbuddy.backend.modules.prompt.model.UserProfileContext;

public interface ProfileContextService {
    UserProfileContext current(String userId, String resumeId);
}
