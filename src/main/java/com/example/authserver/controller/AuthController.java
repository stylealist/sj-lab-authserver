package com.example.authserver.controller;

import com.example.authserver.dto.LoginRequest;
import com.example.authserver.dto.LoginResponse;
import com.example.authserver.service.AuthException;
import com.example.authserver.service.JwtService;
import com.example.authserver.service.QfieldCloudAuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class AuthController {

    private final QfieldCloudAuthService qfieldCloudAuthService;
    private final JwtService jwtService;

    @Value("${auth.cookie.name:sj_session}")
    private String cookieName;

    /**
     * 로컬 http 개발 환경에서는 Secure 쿠키가 저장되지 않으므로 local 프로파일에서만 false로 둔다.
     * 그 밖의 환경(운영 등)은 반드시 true여야 한다.
     */
    @Value("${auth.cookie.secure:true}")
    private boolean cookieSecure;

    /** QFieldCloud 계정(아이디/비밀번호)으로 로그인해 sj-lab 서비스용 JWT를 발급받는다. */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        String username = qfieldCloudAuthService.verifyCredentials(request.username(), request.password());
        String token = jwtService.issueToken(username);
        LoginResponse body = new LoginResponse(token, jwtService.expirationSeconds(), username);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookie(token, jwtService.expirationSeconds()).toString())
                .body(body);
    }

    /** 발급한 토큰이 아직 유효한지 확인한다(다른 서비스가 검증을 위임할 때 쓸 수 있음). */
    @GetMapping("/me")
    public Map<String, String> me(@RequestHeader("Authorization") String authorizationHeader) {
        String token = authorizationHeader.startsWith("Bearer ")
                ? authorizationHeader.substring(7)
                : authorizationHeader;
        return Map.of("username", jwtService.parseUsername(token));
    }

    /**
     * SSO 로그인 페이지({@code /login.html})가 쓴다. 이 서버(로그인 페이지) 자신의 오리진에 이미
     * 유효한 세션 쿠키가 있으면, 로그인 폼을 다시 보여주지 않고 새 토큰을 조용히 발급해 준다.
     * 세션 쿠키가 없거나 만료됐으면 401 — 이때는 로그인 폼을 보여줘야 한다.
     */
    @GetMapping("/session")
    public LoginResponse session(@CookieValue(name = "${auth.cookie.name:sj_session}", required = false) String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) {
            throw AuthException.invalidToken();
        }
        String username = jwtService.parseUsername(sessionToken);
        String token = jwtService.issueToken(username);
        return new LoginResponse(token, jwtService.expirationSeconds(), username);
    }

    /** 이 서버(로그인 페이지) 자신의 세션 쿠키만 지운다 — 각 사이트에 이미 내려준 토큰은 자연 만료될 때까지 유효하다. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        ResponseCookie cleared = ResponseCookie.from(cookieName, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cleared.toString())
                .build();
    }

    private ResponseCookie sessionCookie(String token, long maxAgeSeconds) {
        return ResponseCookie.from(cookieName, token)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAgeSeconds)
                .build();
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String, String>> handleAuthException(AuthException e) {
        HttpStatus status = e.isClientFault() ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(Map.of("message", e.getMessage()));
    }
}
