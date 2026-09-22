package com.example.authserver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * sj-lab 의 로그인 계정은 별도 회원 DB를 두지 않고 QFieldCloud 계정을 그대로 사용한다.
 * 매 로그인 요청마다(고정 서비스 계정이 아니라 사용자가 입력한 아이디/비밀번호로) QFieldCloud 의
 * {@code POST /api/v1/auth/login/} 에 그대로 위임해 검증한다. 이 계약은 mapservice-rest 의
 * QfieldMediaServiceImpl 이 쓰는 것과 동일하다({@code {"username","password"} -> {"token"}}).
 *
 * QFieldCloud 가 내려준 토큰은 여기서는 쓰지 않고 버린다 — sj-lab 서비스들은 이 서버가 새로
 * 발급하는 자체 JWT({@link JwtService})만 신뢰한다.
 */
@Service
@Slf4j
public class QfieldCloudAuthService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Value("${qfield.base-url:https://qfield.sj-lab.co.kr}")
    private String baseUrl;

    /** 로그인 성공 시 QFieldCloud 가 확인해 준 사용자명을 그대로 돌려준다. */
    public String verifyCredentials(String username, String password) {
        String body = String.format("{\"username\":\"%s\",\"password\":\"%s\"}",
                escapeJson(username), escapeJson(password));

        HttpRequest request = HttpRequest.newBuilder(URI.create(trimmedBaseUrl() + "/api/v1/auth/login/"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.error("QFieldCloud 로그인 요청 실패: {}", e.getMessage());
            throw AuthException.upstreamError("QFieldCloud에 연결하지 못했습니다.");
        }

        if (response.statusCode() == 400 || response.statusCode() == 401) {
            throw AuthException.invalidCredentials();
        }
        if (response.statusCode() / 100 != 2) {
            log.error("QFieldCloud 로그인 응답 오류: {}", response.statusCode());
            throw AuthException.upstreamError("QFieldCloud 로그인 확인에 실패했습니다.");
        }

        try {
            String token = objectMapper.readTree(response.body()).path("token").asText("");
            if (token.isBlank()) {
                throw AuthException.invalidCredentials();
            }
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            throw AuthException.upstreamError("QFieldCloud 로그인 응답을 읽지 못했습니다.");
        }

        return username;
    }

    private String trimmedBaseUrl() {
        return baseUrl.replaceAll("/+$", "");
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
