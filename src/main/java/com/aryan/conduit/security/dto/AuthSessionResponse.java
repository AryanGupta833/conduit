package com.aryan.conduit.security.dto;

public record AuthSessionResponse(String accessToken, long expiresInSeconds, AuthUserResponse user) { }
