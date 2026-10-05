package com.aryan.conduit.security.dto;

import java.util.List;

public record MfaRecoveryCodesResponse(List<String> recoveryCodes, boolean reauthenticationRequired) { }
