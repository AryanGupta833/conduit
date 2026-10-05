package com.aryan.conduit.security.service;

import com.aryan.conduit.security.entity.ConduitUser;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class JwtTokenService {
    private final JwtEncoder encoder;
    @Value("${conduit.security.jwt-issuer:conduit}") private String issuer;
    @Value("${conduit.security.access-token-minutes:10}") private long accessTokenMinutes;

    public IssuedAccessToken issue(ConduitUser user) {
        Instant issued = Instant.now();
        Instant expires = issued.plus(Duration.ofMinutes(accessTokenMinutes));
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).subject(user.getEmail())
                .issuedAt(issued).expiresAt(expires).id(java.util.UUID.randomUUID().toString())
                .claim("uid", user.getId()).claim("role", user.getRole().name()).claim("ver", user.getTokenVersion()).build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS512).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedAccessToken(token, expires, accessTokenMinutes * 60);
    }

    public record IssuedAccessToken(String token, Instant expiresAt, long expiresInSeconds) { }
}
