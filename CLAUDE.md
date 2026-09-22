# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트 개요

sj-lab 마이크로서비스 생태계의 로그인 서버(`sj-lab-authserver`)입니다. 별도 회원가입/회원 DB 없이 기존 **QFieldCloud 계정**(`https://qfield.sj-lab.co.kr`)을 로그인 계정으로 그대로 사용합니다. Spring Boot 3.3.2 / Java 17 / Spring Cloud 2023.0.3, Eureka에 `SJ-LAB-AUTHSERVER`로 등록됩니다.

## 빌드 및 실행

```
mvnw.cmd clean package          # 빌드 (target/sj-lab-authserver.jar)
mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local   # 로컬 실행 (local 프로파일 필수 — Eureka localhost:8761)
mvnw.cmd test
```

Docker:
```
mvnw.cmd clean package
docker build -t sj-lab-authserver .
```

## 아키텍처

```
클라이언트 → POST /auth/login {username,password}
           → QfieldCloudAuthService 가 QFieldCloud POST /api/v1/auth/login/ 에 그대로 위임 검증
           → 성공하면 JwtService 가 이 서버만 서명하는 sj-lab 전용 JWT 발급 (QFieldCloud 토큰은 버림)
           → 응답: { accessToken, tokenType: "Bearer", expiresIn, username }
```

- `QfieldCloudAuthService`: 고정 서비스 계정이 아니라 **매 로그인 요청의 사용자 아이디/비밀번호**를 그대로 QFieldCloud 로그인 API에 넘겨 검증한다. `mapservice-rest`의 `QfieldMediaServiceImpl`이 쓰는 것과 같은 API 계약(`{"username","password"} → {"token"}`)을 재사용했다. 400/401은 "아이디 또는 비밀번호가 올바르지 않습니다"로 401 매핑, 그 외 오류는 502.
- `JwtService`: `AUTH_JWT_SECRET`(HS256, 최소 32바이트)으로 서명하는 자체 JWT를 발급·검증한다. QFieldCloud가 내려준 토큰과는 별개이며, sj-lab 서비스들은 이 JWT만 신뢰하도록 설계한다.
- `SecurityConfig`: 이 서버 자신은 모든 요청을 permitAll로 열어 둔다(로그인 엔드포인트이므로). CSRF는 비활성화.

## 현재 범위와 남은 작업 (중요)

이 저장소는 **"로그인 서버 발급 + 게이트웨이 라우팅"까지만** 구축된 상태입니다(2026-09-22, 사용자 확정 범위). 다음은 아직 하지 않았습니다 — 의도적인 제한이니 마음대로 확장하지 말고, 필요하면 사용자에게 먼저 확인할 것:

- **다른 서비스(mapservice-rest, scheduler 등) API에 토큰 검증 강제 없음** — 지금은 로그인·토큰 발급만 되고, 게이트웨이나 다른 서비스가 이 토큰을 요구하지 않는다. 전면 적용하려면 게이트웨이에 전역 필터(JWT 검증 후 헤더로 사용자명 전달 등)를 추가해야 하고, `sj-lab-mapservice`(프론트)에 로그인 화면도 먼저 만들어야 한다.
- **회원 정보 캐시/역할(권한) 개념 없음** — 지금은 QFieldCloud 로그인 성공 여부만 확인하고 `username`만 다룬다. 역할(관리자/담당자 등)이 필요해지면 이 서버에 별도 사용자 프로필 테이블을 추가할지, QFieldCloud 응답의 다른 필드를 쓸지 결정이 필요하다.
- **리프레시 토큰 없음** — 만료되면 다시 `/auth/login`을 호출해야 한다.
- **운영 프로파일(application-prod.yml) 없음** — 로컬(`local`)만 있다. 배포하려면 게이트웨이/스케줄러처럼 운영 Eureka 주소(`eureka.sj-lab.co.kr`)를 추가하고, `AUTH_JWT_SECRET`을 k8s Secret으로 주입해야 한다(저장소가 public이므로 절대 파일에 평문으로 넣지 말 것).

## 참고

- QFieldCloud base URL(`https://qfield.sj-lab.co.kr`)은 이미 다른 public 저장소(`mapservice-rest`)에도 공개돼 있어 비밀값이 아니다. 반면 `AUTH_JWT_SECRET`은 이 서버가 발급하는 세션 토큰의 서명 키이므로 반드시 환경변수로만 주입하고 저장소 파일에 적지 말 것.
- 테스트는 `AuthServerApplicationTests` 컨텍스트 로딩 스모크 테스트뿐이다.

## 통합 허브

저장소를 넘나드는 작업(DB → 백엔드 → 디스커버리 → 게이트웨이 → 프론트엔드)의 총괄 기준 저장소는 `C:\developer\workspace\mapservice-rest`입니다. 시스템 전체 구조·API 계약은 그 저장소의 `docs/system-architecture.md`, 로컬 포트·기동 순서·CORS는 `docs/dev-environment.md`에 있고, MCP(GitHub/DB)와 로컬 비밀값도 그 저장소에서만 관리합니다.
