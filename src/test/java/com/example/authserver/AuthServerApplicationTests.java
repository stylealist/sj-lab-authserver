package com.example.authserver;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// JwtService 가 기동 시 local 프로파일이 아니면 기본(로컬 전용) 시크릿을 거부하므로,
// 스모크 테스트도 local 프로파일로 띄운다.
@SpringBootTest
@ActiveProfiles("local")
class AuthServerApplicationTests {

    @Test
    void contextLoads() {
    }
}
