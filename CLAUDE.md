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

로컬에서는 총괄 허브의 `mapservice-rest\scripts\local-stack.ps1 start`가 이 서버까지 함께 띄운다(없으면 hub·mapservice 로그인 게이트 때문에 접속 자체가 안 됨). 비밀값 없이도 일반 로그인은 되고(JWT 키는 `local` 프로파일 기본값), 체험용 버튼만 `AUTH_DEMO_*`가 필요하며 스크립트가 허브의 `.claude\settings.local.json` `env`에서 주입한다.

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
           → 응답: { accessToken, tokenType: "Bearer", expiresIn, username } + Set-Cookie(세션 쿠키)
```

- `QfieldCloudAuthService`: 고정 서비스 계정이 아니라 **매 로그인 요청의 사용자 아이디/비밀번호**를 그대로 QFieldCloud 로그인 API에 넘겨 검증한다. `mapservice-rest`의 `QfieldMediaServiceImpl`이 쓰는 것과 같은 API 계약(`{"username","password"} → {"token"}`)을 재사용했다. 400/401은 "아이디 또는 비밀번호가 올바르지 않습니다"로 401 매핑, 그 외 오류는 502.
- `JwtService`: `AUTH_JWT_SECRET`(HS256, 최소 32바이트)으로 서명하는 자체 JWT를 발급·검증한다. QFieldCloud가 내려준 토큰과는 별개이며, sj-lab 서비스들은 이 JWT만 신뢰하도록 설계한다.
- `SecurityConfig`: 이 서버 자신은 모든 요청을 permitAll로 열어 둔다(로그인 엔드포인트이므로). CSRF는 비활성화. **Spring Security 기본 로그아웃 필터도 명시적으로 꺼야 한다** — 안 끄면 `POST /logout`을 우리 `AuthController#logout()`보다 먼저 가로채 `/login?logout`으로 리다이렉트해 버린다(직접 겪은 문제, `.logout(AbstractHttpConfigurer::disable)` 참고).

### SSO(사이트 간 자동 로그인) — 2026-09-22 추가

`sj-lab-hub`, `sj-lab-mapservice`가 각각 로그인 화면을 갖는 대신, **이 서버가 서빙하는 하나의 로그인 페이지**(`GET /login.html`, `src/main/resources/static/login.html`)로 리다이렉트 방식으로 로그인한다. 별도 SSO 라이브러리나 공유 쿠키 도메인 설정 없이, 다음 흐름으로 "한 곳에서 로그인하면 다른 사이트도 로그인 상태"를 구현했다.

```
1. hub/mapservice 접속 시 로컬(localStorage)에 유효한 토큰이 없으면
   /auth/login.html?redirect_uri=<원래 주소> 로 리다이렉트 (프론트 쪽 gate, 아래 참고)
2. login.html 은 자기 자신의 오리진(=게이트웨이)에 세션 쿠키가 있는지 GET /auth/session 으로 먼저 확인
   - 있으면: 폼을 보여주지 않고 새 토큰을 받아 바로 3번으로
   - 없으면: 로그인 폼 표시 → 제출 시 POST /auth/login (성공하면 쿠키도 같이 내려옴)
3. login.html 이 redirect_uri 로 다시 이동하면서 토큰을 URL 해시(#auth_token=...)에 실어 보냄
4. 원래 사이트의 gate 스크립트가 해시에서 토큰을 꺼내 localStorage 에 저장하고 해시를 지움
```

- **쿠키(`sj_session`)는 이 로그인 페이지 자신의 오리진에만 쓰인다** — hub/mapservice 로는 전혀 전달되지 않는다(각 사이트는 3번 단계의 URL 해시로 받은 토큰을 **자기 localStorage**에 각자 저장). 그래서 쿠키의 `SameSite`/`Secure`/도메인 공유를 고민할 필요가 없다 — 로그인 페이지 자기 자신에게 다시 접속할 때만(=최상위 탐색, `SameSite=Lax`로 충분) "이미 로그인했는지" 확인하는 용도.
- `GET /session`: 쿠키만으로 인증(Authorization 헤더 불필요). 유효하면 **새 토큰을 발급**해 돌려준다(리프레시처럼 동작).
- **로그아웃 흐름**: 각 사이트의 `SjLabAuth.logout()`은 자기 localStorage를 지운 뒤 `/auth/login.html?...&logout=1`로 이동하고, `login.html`이 `logout=1`을 보면 세션 확인 대신 `POST /auth/logout`으로 세션 쿠키를 지운 뒤 폼을 보여준다. **이 단계를 빼면 로그인 페이지가 살아있는 세션 쿠키로 곧바로 새 토큰을 발급해 되돌려 보내 로그아웃이 안 된다**(2026-09-22 실제 발생).
- `POST /logout`: 이 서버(로그인 페이지)의 세션 쿠키만 지운다. **각 사이트가 이미 받아 간 토큰까지 무효화하지는 않는다** — 진짜 single-logout(다른 탭/사이트까지 전부 로그아웃)은 구현하지 않았다(아래 남은 작업 참고). 프론트의 `SjLabAuth.logout()`은 자기 localStorage를 지우고 로그인 페이지로 보내는 것까지만 한다.
- **체험용 계정(포트폴리오용)**: `login.html`의 "체험용 계정으로 로그인" 버튼은 `POST /auth/login/demo`만 호출하고, 계정 정보는 서버가 `AUTH_DEMO_USERNAME`/`AUTH_DEMO_PASSWORD`(운영은 k8s Secret `auth-demo-credentials`, optional)에서 읽는다. **아이디·비밀번호를 `login.html`이나 저장소 파일에 적지 말 것**(public 저장소 — 2026-09-22 처음엔 페이지 JS에 넣었다가 서버 처리로 바꿈. 그 기록이 git 히스토리에 남아 있으므로 QFieldCloud에서 데모 비밀번호를 교체했어야 함). 미설정이면 데모 버튼만 503. 데모 계정은 **QFieldCloud 어떤 프로젝트에도 멤버로 넣지 말 것**(우리 로그인은 계정 존재만 확인하므로 프로젝트 권한 없이도 동작). 백엔드 API는 토큰을 강제하지 않아 체험 사용자도 내업 등록·수정·삭제가 가능하다는 점에 유의.
- `redirect_uri` 오픈 리다이렉트 방지: `login.html`이 허용 오리진 목록(`localhost:3000`, `localhost:4000`, `https://sj-lab.co.kr`, `https://www.sj-lab.co.kr`)에 없는 `redirect_uri`는 거부한다. 새 프론트 도메인을 추가하면 `login.html`의 `ALLOWED_REDIRECT_ORIGINS`도 함께 고칠 것.
- 프론트 쪽 gate 스크립트(`sj-lab-mapservice`의 `js/auth-gate.js`, `sj-lab-hub`의 `public/index.html` 인라인 스크립트)는 이 저장소가 아니라 각 프론트 저장소에 있다 — 로직은 동일하지만 빌드 도구가 달라(하나는 무빌드 정적 사이트, 하나는 webpack) 공유 모듈 대신 내용을 복제했다. 한쪽을 고치면 다른 쪽도 함께 고칠 것.

## 현재 범위와 남은 작업 (중요)

2026-09-22 기준 **"로그인 서버 + SSO 리다이렉트 로그인 페이지 + hub/mapservice 전면 게이트"까지** 구축했습니다(사용자 확정 범위). 다음은 의도적으로 하지 않았습니다 — 마음대로 확장하지 말고, 필요하면 사용자에게 먼저 확인할 것:

- **백엔드 API(mapservice-rest, scheduler)에 토큰 검증 강제 없음** — hub/mapservice는 화면 접근을 막지만(프론트 UX 게이트), API를 직접 호출하면 토큰 없이도 그대로 동작한다. 진짜 보안 경계가 필요해지면 게이트웨이 전역 필터(JWT 검증 후 헤더로 사용자명 전달 등)를 추가해야 한다.
- **진짜 single-logout 없음** — `/auth/logout`은 로그인 페이지 자신의 쿠키만 지운다. 한 사이트에서 로그아웃해도 다른 사이트는 토큰이 자연 만료(기본 12시간)될 때까지 로그인 상태로 남는다. 전 사이트 동시 로그아웃이 필요하면 iframe 기반 로그아웃 전파 등을 추가로 설계해야 한다.
- **회원 정보 캐시/역할(권한) 개념 없음** — 지금은 QFieldCloud 로그인 성공 여부만 확인하고 `username`만 다룬다.
- **리프레시 토큰 없음** — `GET /session`이 쿠키 기반으로 비슷한 역할을 하지만, 쿠키 자체가 없는 상태(예: 다른 브라우저)에서는 다시 `/auth/login`을 호출해야 한다.
- **운영 프로파일(application-prod.yml) 없음** — 로컬(`local`)만 있다. 배포하려면 게이트웨이/스케줄러처럼 운영 Eureka 주소(`eureka.sj-lab.co.kr`)를 추가하고, `AUTH_JWT_SECRET`을 k8s Secret으로 주입해야 한다(저장소가 public이므로 절대 파일에 평문으로 넣지 말 것). `JwtService.validateSecret()`이 `local` 프로파일이 아닌데 기본 시크릿이면 기동 자체를 막으니, 배포 전 Secret을 빼먹으면 기동 실패 로그로 바로 드러난다. `auth.cookie.secure`도 마찬가지로 운영에서는 반드시 `true`(기본값)여야 한다.
- **`/auth/login`에 레이트 리미팅/잠금 없음** — 매 요청이 실제 QFieldCloud 로그인 API로 그대로 전달되므로, 지금 상태로 외부에 노출하면 무차별 대입 공격 통로가 된다. 로컬/사내망 밖으로 열기 전에 IP 또는 계정 단위 속도 제한을 추가할 것.

## 참고

- QFieldCloud base URL(`https://qfield.sj-lab.co.kr`)은 이미 다른 public 저장소(`mapservice-rest`)에도 공개돼 있어 비밀값이 아니다. 반면 `AUTH_JWT_SECRET`은 이 서버가 발급하는 세션 토큰의 서명 키이므로 반드시 환경변수로만 주입하고 저장소 파일에 적지 말 것.
- 테스트는 `AuthServerApplicationTests` 컨텍스트 로딩 스모크 테스트뿐이다.

## 통합 허브

저장소를 넘나드는 작업(DB → 백엔드 → 디스커버리 → 게이트웨이 → 프론트엔드)의 총괄 기준 저장소는 `C:\developer\workspace\mapservice-rest`입니다. 시스템 전체 구조·API 계약은 그 저장소의 `docs/system-architecture.md`, 로컬 포트·기동 순서·CORS는 `docs/dev-environment.md`에 있고, MCP(GitHub/DB)와 로컬 비밀값도 그 저장소에서만 관리합니다.
