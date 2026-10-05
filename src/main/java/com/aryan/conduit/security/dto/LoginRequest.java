package com.aryan.conduit.security.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(@Email @NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 72) String password,
                           @Size(max = 32) String totpCode) { }
