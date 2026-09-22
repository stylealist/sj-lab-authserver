package com.example.authserver.service;

/** 인증 관련 실패. {@link #invalidCredentials()}/{@link #invalidToken()}는 401로, 그 외는 502로 매핑한다. */
public class AuthException extends RuntimeException {

    private final boolean clientFault;

    private AuthException(String message, boolean clientFault) {
        super(message);
        this.clientFault = clientFault;
    }

    public static AuthException invalidCredentials() {
        return new AuthException("아이디 또는 비밀번호가 올바르지 않습니다.", true);
    }

    public static AuthException invalidToken() {
        return new AuthException("유효하지 않거나 만료된 토큰입니다.", true);
    }

    public static AuthException upstreamError(String message) {
        return new AuthException(message, false);
    }

    public boolean isClientFault() {
        return clientFault;
    }
}
