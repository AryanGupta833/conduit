package com.aryan.conduit.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record ResetPasswordRequest(@NotBlank @Size(min = 12, max = 72) String newPassword) { }
