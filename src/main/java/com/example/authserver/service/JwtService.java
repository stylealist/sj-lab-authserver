package com.example.authserver.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * sj-lab 서비스 전용 세션 토큰 발급·검증. QFieldCloud 가 내려주는 토큰과는 별개이며,
 * 이 서버만 서명·검증한다. 게이트웨이/다른 서비스가 검증을 강제하는 것은 이번 범위 밖이다.
 */
@Service
public class JwtService {

    @Value("${auth.jwt.secret}")
    private String secret;

    @Value("${auth.jwt.expiration-seconds:43200}")
    private long expirationSeconds;

    private SecretKey key() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String issueToken(String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirationSeconds)))
                .signWith(key())
                .compact();
    }

    public long expirationSeconds() {
        return expirationSeconds;
    }

    /** 유효하지 않거나 만료된 토큰이면 예외를 던진다. */
    public String parseUsername(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return claims.getSubject();
        } catch (JwtException | IllegalArgumentException e) {
            throw AuthException.invalidToken();
        }
    }
}
