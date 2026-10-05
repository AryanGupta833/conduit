package com.aryan.conduit.security.dto;

import com.aryan.conduit.security.entity.AccountRole;

public record AuthUserResponse(Long id, String email, AccountRole role, boolean enabled, boolean mfaEnabled) { }
