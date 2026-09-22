# sj-lab-authserver

sj-lab 마이크로서비스 생태계를 위한 로그인 서버입니다. 별도 회원 DB를 두지 않고, 기존 **QFieldCloud 계정**(`https://qfield.sj-lab.co.kr`)을 그대로 로그인 계정으로 사용합니다.

- `GET /auth/login.html` — sj-lab 전체가 공유하는 로그인 페이지(SSO). `redirect_uri` 파라미터로 원래 사이트에 토큰을 실어 돌려보냅니다.
- `POST /auth/login` — `{ "username", "password" }`를 받아 QFieldCloud `/api/v1/auth/login/`에 위임 검증하고, 성공하면 sj-lab 전용 JWT를 발급합니다(세션 쿠키도 함께 내려줌).
- `GET /auth/session` — 세션 쿠키만으로 이미 로그인돼 있는지 확인하고, 유효하면 새 토큰을 발급합니다(로그인 페이지 재방문 시 폼을 다시 보여주지 않기 위함).
- `POST /auth/login/demo` — 체험용 계정으로 로그인합니다(계정 정보는 서버 환경변수에만 있음).
- `POST /auth/logout` — 로그인 페이지의 세션 쿠키를 지웁니다.
- `GET /auth/me` — `Authorization: Bearer <token>`으로 토큰 유효성을 확인합니다.

`sj-lab-hub`, `sj-lab-mapservice`는 접속 시 로컬 토큰이 없으면 `/auth/login.html`로 리다이렉트되고, 로그인 성공 시 원래 사이트로 토큰을 받아 돌아옵니다(SSO). 자세한 흐름은 `CLAUDE.md`의 "SSO" 절 참고.

Spring Boot 3.3.2 / Java 17 / Spring Cloud 2023.0.3, Eureka에 `SJ-LAB-AUTHSERVER`로 등록됩니다. 자세한 아키텍처와 범위는 `CLAUDE.md`를 참고하세요.

## 빌드 및 실행

```
mvnw.cmd clean package
mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local
```

로컬 실행 시 `local` 프로파일이 있어야 Eureka(`localhost:8761`) 주소가 채워집니다. 보통은 총괄 허브의 `mapservice-rest\scripts\local-stack.ps1 start`가 이 서버까지 함께 띄웁니다. 비밀값 없이도 일반 로그인은 되고, 체험용 버튼만 `AUTH_DEMO_USERNAME`/`AUTH_DEMO_PASSWORD`가 필요합니다(허브의 `.claude\settings.local.json` `env`에서 주입).

## 환경변수

| 변수 | 설명 | 필수 |
|---|---|---|
| `AUTH_JWT_SECRET` | 이 서버가 발급하는 JWT 서명 키(HS256, 최소 32바이트) | 운영 필수(로컬은 기본값 사용 가능, 저장소가 public이라 기본값은 안전하지 않음) |
| `AUTH_JWT_EXPIRATION_SECONDS` | 토큰 만료(초), 기본 43200(12시간) | 선택 |
| `AUTH_DEMO_USERNAME` / `AUTH_DEMO_PASSWORD` | 로그인 페이지 "체험용 계정으로 로그인"(`POST /auth/login/demo`)이 쓰는 QFieldCloud 계정 | 선택(없으면 데모 버튼만 503) |
