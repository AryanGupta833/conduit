package com.aryan.conduit.security.dto;

import com.aryan.conduit.security.entity.AccountRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(@Email @NotBlank @Size(max = 254) String email,
                                   @NotBlank @Size(min = 12, max = 72) String password,
                                   @NotNull AccountRole role) { }
