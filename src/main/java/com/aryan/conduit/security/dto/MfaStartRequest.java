package com.aryan.conduit.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MfaStartRequest(@NotBlank @Size(max = 72) String password) { }
