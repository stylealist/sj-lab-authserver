package com.example.authserver.dto;

public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        String username
) {
    public LoginResponse(String accessToken, long expiresInSeconds, String username) {
        this(accessToken, "Bearer", expiresInSeconds, username);
    }
}
