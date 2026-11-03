package com.malldemo.user.dto.response;

import lombok.Data;

@Data
public class UserLoginResponse {

    private final String accessToken;

    private final String tokenType;

    private final long expiresInSeconds;

    private final UserResponse user;

    public UserLoginResponse(
            String accessToken,
            String tokenType,
            long expiresInSeconds,
            UserResponse user
    ) {
        this.accessToken = accessToken;
        this.tokenType = tokenType;
        this.expiresInSeconds = expiresInSeconds;
        this.user = user;
    }
}
