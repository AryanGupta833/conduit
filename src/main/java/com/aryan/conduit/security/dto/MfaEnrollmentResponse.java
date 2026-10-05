package com.aryan.conduit.security.dto;

public record MfaEnrollmentResponse(String secret, String otpauthUri) { }
