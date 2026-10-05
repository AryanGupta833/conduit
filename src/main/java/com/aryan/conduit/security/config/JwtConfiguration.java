package com.aryan.conduit.security.config;

import com.aryan.conduit.security.repository.ConduitUserRepository;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Configuration
public class JwtConfiguration {
    @Bean
    SecretKey conduitJwtKey(@Value("${conduit.security.jwt-secret:}") String encoded) {
        try {
            byte[] key = Base64.getDecoder().decode(encoded);
            if (key.length < 64) throw new IllegalArgumentException();
            return new SecretKeySpec(key, "HmacSHA512");
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("Set CONDUIT_JWT_SECRET to a base64-encoded random key of at least 64 bytes");
        }
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey conduitJwtKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(conduitJwtKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey conduitJwtKey,
                          ConduitUserRepository users,
                          @Value("${conduit.security.jwt-issuer:conduit}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(conduitJwtKey)
                .macAlgorithm(MacAlgorithm.HS512).build();
        OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> accountValidator = jwt -> {
            try {
                Number userId = jwt.getClaim("uid");
                Number tokenVersion = jwt.getClaim("ver");
                if (userId == null || tokenVersion == null) return invalidToken();
                var user = users.findById(userId.longValue()).orElse(null);
                if (user == null || !user.isEnabled() || user.getTokenVersion() != tokenVersion.intValue()
                        || !user.getEmail().equalsIgnoreCase(jwt.getSubject())
                        || !user.getRole().name().equals(jwt.getClaimAsString("role"))) return invalidToken();
                return OAuth2TokenValidatorResult.success();
            } catch (RuntimeException ignored) { return invalidToken(); }
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuerValidator, accountValidator));
        return decoder;
    }

    private static OAuth2TokenValidatorResult invalidToken() {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "The account is no longer active", null));
    }

    @Bean
    Converter<Jwt, ? extends AbstractAuthenticationToken> conduitJwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + jwt.getClaimAsString("role"))));
        converter.setPrincipalClaimName("sub");
        return converter;
    }
}
