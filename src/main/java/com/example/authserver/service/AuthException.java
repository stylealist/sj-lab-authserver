package com.example.authserver.service;

import org.springframework.http.HttpStatus;

/** 인증 관련 실패. 응답 상태 코드를 함께 싣는다(AuthController#handleAuthException). */
public class AuthException extends RuntimeException {

    private final HttpStatus status;

    private AuthException(String message, HttpStatus status) {
        super(message);
        this.status = status;
    }

    public static AuthException invalidCredentials() {
        return new AuthException("아이디 또는 비밀번호가 올바르지 않습니다.", HttpStatus.UNAUTHORIZED);
    }

    public static AuthException invalidToken() {
        return new AuthException("유효하지 않거나 만료된 토큰입니다.", HttpStatus.UNAUTHORIZED);
    }

    public static AuthException upstreamError(String message) {
        return new AuthException(message, HttpStatus.BAD_GATEWAY);
    }

    public static AuthException demoNotConfigured() {
        return new AuthException("체험용 계정이 설정되지 않았습니다.", HttpStatus.SERVICE_UNAVAILABLE);
    }

    public HttpStatus status() {
        return status;
    }
}
