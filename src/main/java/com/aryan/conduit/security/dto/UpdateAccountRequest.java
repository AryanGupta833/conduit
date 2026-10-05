package com.aryan.conduit.security.dto;

import com.aryan.conduit.security.entity.AccountRole;
import jakarta.validation.constraints.NotNull;

public record UpdateAccountRequest(@NotNull AccountRole role, boolean enabled) { }
