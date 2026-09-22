package com.example.authserver.controller;

import com.example.authserver.dto.LoginRequest;
import com.example.authserver.dto.LoginResponse;
import com.example.authserver.service.AuthException;
import com.example.authserver.service.JwtService;
import com.example.authserver.service.QfieldCloudAuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class AuthController {

    private final QfieldCloudAuthService qfieldCloudAuthService;
    private final JwtService jwtService;

    /** QFieldCloud 계정(아이디/비밀번호)으로 로그인해 sj-lab 서비스용 JWT를 발급받는다. */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        String username = qfieldCloudAuthService.verifyCredentials(request.username(), request.password());
        String token = jwtService.issueToken(username);
        return new LoginResponse(token, jwtService.expirationSeconds(), username);
    }

    /** 발급한 토큰이 아직 유효한지 확인한다(다른 서비스가 검증을 위임할 때 쓸 수 있음). */
    @GetMapping("/me")
    public Map<String, String> me(@RequestHeader("Authorization") String authorizationHeader) {
        String token = authorizationHeader.startsWith("Bearer ")
                ? authorizationHeader.substring(7)
                : authorizationHeader;
        return Map.of("username", jwtService.parseUsername(token));
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String, String>> handleAuthException(AuthException e) {
        HttpStatus status = e.isClientFault() ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(Map.of("message", e.getMessage()));
    }
}
