package com.aryan.conduit.security.service;

public class RefreshTokenRejectedException extends RuntimeException {
    public RefreshTokenRejectedException() { super("Refresh token is invalid or expired"); }
}
