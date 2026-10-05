package com.aryan.conduit.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MfaDisableRequest(@NotBlank @Size(max = 72) String password,
                                @NotBlank @Size(max = 32) String code) { }
