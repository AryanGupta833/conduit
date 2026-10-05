package com.aryan.conduit.security.service;

public class AuthenticationRejectedException extends RuntimeException {
    public AuthenticationRejectedException() { super("Invalid email or password"); }
}
