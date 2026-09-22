package com.example.authserver.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
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

    /** application.yml 의 로컬 전용 기본값과 반드시 같은 문자열이어야 한다. */
    private static final String INSECURE_DEFAULT_SECRET =
            "local-dev-only-insecure-secret-please-override-32bytes-min";

    /** HS256 서명에 필요한 최소 키 길이(256bit). 이보다 짧으면 서명 시점에 WeakKeyException 이 난다. */
    private static final int MIN_SECRET_BYTES = 32;

    @Value("${auth.jwt.secret}")
    private String secret;

    @Value("${auth.jwt.expiration-seconds:43200}")
    private long expirationSeconds;

    private final Environment environment;

    private SecretKey signingKey;

    public JwtService(Environment environment) {
        this.environment = environment;
    }

    /**
     * 기동 시점에 한 번만 검증한다 — 짧은 시크릿을 로그인마다 실패하는 500 대신 기동 실패로 막고,
     * 로컬 전용 기본값이 local 프로파일이 아닌 곳에 그대로 배포되는 것(운영에서 시크릿 주입 누락)을 막는다.
     */
    @PostConstruct
    void validateSecret() {
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "auth.jwt.secret(AUTH_JWT_SECRET)은 최소 " + MIN_SECRET_BYTES + "바이트 이상이어야 합니다.");
        }

        boolean isLocal = environment.acceptsProfiles(Profiles.of("local"));
        if (INSECURE_DEFAULT_SECRET.equals(secret) && !isLocal) {
            throw new IllegalStateException(
                    "auth.jwt.secret 이 로컬 전용 기본값입니다. local 프로파일이 아니면 AUTH_JWT_SECRET 환경변수를 "
                            + "반드시 지정해야 합니다(이 저장소는 public이라 실제 서명 키가 코드에 없습니다).");
        }

        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    private SecretKey key() {
        return signingKey;
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
