package com.jobbuddy.backend.modules.auth.dto.response;

import com.jobbuddy.backend.common.security.AuthenticatedUser;
import lombok.Data;

@Data
public class CurrentUserResponse {
    private String userId;
    private String username;
    private String displayName;
    private String role;

    public static CurrentUserResponse from(AuthenticatedUser user) {
        CurrentUserResponse response = new CurrentUserResponse();
        response.setUserId(user.getUserId());
        response.setUsername(user.getUsername());
        response.setDisplayName(user.getDisplayName());
        response.setRole(user.getRole());
        return response;
    }
}
