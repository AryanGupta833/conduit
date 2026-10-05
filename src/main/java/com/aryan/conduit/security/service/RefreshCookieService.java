package com.aryan.conduit.security.service;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import java.time.Duration;

@Component
public class RefreshCookieService {
    public static final String COOKIE_NAME = "CONDUIT_REFRESH";
    @Value("${conduit.security.refresh-token-days:14}") private long refreshDays;
    @Value("${conduit.security.refresh-cookie-secure:true}") private boolean secure;
    public void set(HttpServletResponse response, String token) {
        response.addHeader("Set-Cookie", ResponseCookie.from(COOKIE_NAME, token).httpOnly(true).secure(secure)
                .sameSite("Strict").path("/api/auth").maxAge(Duration.ofDays(refreshDays)).build().toString());
    }
    public void clear(HttpServletResponse response) {
        response.addHeader("Set-Cookie", ResponseCookie.from(COOKIE_NAME, "").httpOnly(true).secure(secure)
                .sameSite("Strict").path("/api/auth").maxAge(Duration.ZERO).build().toString());
    }
}
