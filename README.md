# sj-lab-authserver — 통합 인증 및 단일 로그인(SSO) 서비스

`sj-lab-authserver`는 sj-lab 분산 웹 플랫폼(랜딩 허브, 지도 서비스 등)의 인증을 통합 관리하는 Spring Boot 기반의 인증 서비스입니다. 별도의 사용자 데이터베이스를 구축하지 않고 기존 QFieldCloud 인증 API를 위임 검증(Delegated Authentication)하여 sj-lab 전용 JWT를 발급하며, 1st-Party 세션 쿠키와 URL Fragment 기반의 경량 단일 로그인(SSO) 아키텍처를 제공합니다.

---

## 1. 서비스 역할 및 핵심 책임

- **위임 기반 계정 검증(Delegated Authentication)**: 별도의 회원 DB 없이 현장조사 플랫폼(QFieldCloud)의 인증 엔드포인트를 호출하여 계정 유효성을 검증하고, 검증 성공 시 내부 서비스 전용 JWT를 생성·발급합니다.
- **경량 무상태 단일 로그인(SSO)**: 여러 서브도메인과 정적 SPA 사이트 간에 추가적인 복잡한 외부 솔루션 없이 세션 쿠키와 리다이렉트 메커니즘을 결합하여 매끄러운 단일 로그인 경험을 제공합니다.
- **안전한 체험 계정 및 자격증명 관리**: 방문자용 데모 로그인을 서버 환경변수 기반 엔드포인트(`POST /auth/login/demo`)로 격리하여 프론트엔드 자바스크립트 코드 내 민감정보 노출을 방지합니다.
- **오픈 리다이렉트 차단 및 보안 경계**: 승인된 허용 도메인(`ALLOWED_ORIGINS`)에 대해서만 로그인 후 리다이렉트를 허용하여 보안 취약점을 차단합니다.

---

## 2. 기술 스택

- **언어 및 런타임**: Java 17, Spring Boot 3.3.2
- **보안 및 토큰**: Spring Security 6, JJWT 0.12.6 (HMAC-SHA256)
- **서비스 디스커버리**: Spring Cloud Netflix Eureka Client
- **배포 환경**: Docker, Kubernetes (ClusterIP 80 -> 8080), Helm, Jenkins CI, ArgoCD (GitOps)

---

## 3. 인증 및 SSO(Single Sign-On) 프로세스

### 3.1 전체 SSO 인증 시퀀스

```
[클라이언트 SPA] (sj-lab.co.kr / map)             [sj-lab-authserver] (/auth)         [QFieldCloud API]
       │                                                    │                                │
       │ 1. 토큰 부재 감지                                  │                                │
       ├───────────────── 리다이렉트 ──────────────────────>│                                │
       │ (login.html?redirect_uri=...)                      │                                │
       │                                                    │ 2. 세션 쿠키(sj_session) 확인  │
       │                                                    │    (미보유 시 로그인 폼 표시)  │
       │                                                    │                                │
       │ 3. ID / PW 로그인 제출                             │                                │
       ├───────────────── POST /auth/login ────────────────>│                                │
       │                                                    │ 4. 위임 검증 요청              │
       │                                                    ├────── POST /api/v1/auth ──────>│
       │                                                    │<───── 200 OK (검증 성공) ──────┤
       │                                                    │                                │
       │                                                    │ 5. sj-lab 전용 JWT 생성         │
       │                                                    │    세션 쿠키 발급              │
       │ 6. 원래 URL로 리다이렉트                           │                                │
       │<── redirect_uri#auth_token=JWT&expires=... ────────┤                                │
       │                                                    │                                │
 7. 해시 파싱 후                                            │                                │
    localStorage 저장                                       │                                │
 8. 서비스 정상 이용                                        │                                │
```

### 3.2 분리형 세션-토큰 아키텍처
- **세션 쿠키 (`sj_session`)**: 인증 서버 자신의 오리진(`/auth`)에만 `HttpOnly; SameSite=Lax` 속성으로 보관되며, "이 브라우저가 현재 로그인 상태인가"를 판별하는 용도로만 사용됩니다.
- **JWT 액세스 토큰**: 각 정적 웹 서비스의 `localStorage`에 개별 저장되어 API 호출 시 `Authorization: Bearer <TOKEN>` 헤더로 전송됩니다. 크로스 도메인 쿠키 공유나 서드파티 쿠키 차단 문제를 근본적으로 회피합니다.

---

## 4. 핵심 엔지니어링 구현 상세

### 4.1 안전하지 않은 시크릿 기동 방지 (Fail-Fast Validation)
운영 환경에서 기본 시크릿이 사용되거나 32바이트(256비트) 미만의 취약한 서명 키가 주입되는 사고를 방지하기 위해, `@PostConstruct` 단계에서 키 유효성을 검증합니다. 검증 실패 시 스프링 컨텍스트 기동을 강제 중단하여 쿠버네티스 파드가 배포 단계에서 `CreateContainerConfigError`로 즉시 드러나도록 설계했습니다.

### 4.2 로그아웃 동기화 및 Spring Security 충돌 해결
- 프론트엔드에서 `localStorage`만 삭제할 경우 살아있는 세션 쿠키에 의해 재방문 시 즉각 토큰이 재발급되는 문제를 방지하기 위해, 로그아웃 진입 시 `logout=1` 파라미터를 동반하여 `POST /auth/logout`을 통해 서버 세션 쿠키를 명시적으로 파기한 후 로그인 화면으로 전환합니다.
- Spring Security의 기본 로그아웃 필터가 `/auth/logout` 요청을 가로채지 않도록 `http.logout(AbstractHttpConfigurer::disable)` 설정을 적용하고 컨트롤러 계층에서 세션 쿠키 무효화를 완전 제어합니다.

### 4.3 오픈 리다이렉트(Open Redirect) 방어
인증 후 리턴 주소(`redirect_uri`) 파라미터에 대한 피싱 공격을 방지하기 위해, 사전에 등록된 화이트리스트 오리진(`localhost:3000`, `localhost:4000`, `sj-lab.co.kr`, `www.sj-lab.co.kr`)과의 일치 여부를 정밀 검증합니다.

---

## 5. API 명세

| 메서드 | 경로 | 설명 | 파라미터 및 응답 |
|---|---|---|---|
| `GET` | `/auth/login.html` | 중앙 로그인 웹 페이지 | `redirect_uri` (로그인 완료 후 복귀 주소) |
| `POST` | `/auth/login` | QFieldCloud 위임 로그인 | Request: `{username, password}`<br>Response: `{accessToken, expiresIn, username}` |
| `POST` | `/auth/login/demo` | 서버 환경변수 기반 데모 로그인 | Request: 빈 바디 (서버 Secret 기반 인증) |
| `GET` | `/auth/session` | 세션 쿠키 기반 로그인 검증 | 세션 쿠키 유효 시 신규 JWT 즉시 반환 |
| `POST` | `/auth/logout` | 인증 세션 쿠키 파기 | `Set-Cookie: sj_session=; Max-Age=0` |
| `GET` | `/auth/me` | JWT 토큰 유효성 검증 | Header: `Authorization: Bearer <TOKEN>` |

---

## 6. 실행 및 환경 구성

### 로컬 빌드 및 실행
```powershell
# Maven 빌드
mvnw.cmd clean package

# 로컬 실행 (Eureka: localhost:8761 등록, 포트 랜덤)
mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local
```

### 주요 환경 변수
- `AUTH_JWT_SECRET`: JWT 서명용 비밀키 (HS256 32자 이상, 운영 환경 필수)
- `AUTH_JWT_EXPIRATION_SECONDS`: 토큰 유효기간 (기본값 43200초 / 12시간)
- `AUTH_DEMO_USERNAME`: 체험용 QFieldCloud 계정 ID
- `AUTH_DEMO_PASSWORD`: 체험용 QFieldCloud 계정 비밀번호
- `QFIELD_BASE_URL`: 인증 위임 대상 URL (기본값 `https://qfield.sj-lab.co.kr`)
