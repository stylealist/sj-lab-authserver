# sj-lab-authserver — 로그인 서버 + SSO

> **회원 DB 없이** 기존 QFieldCloud 계정을 그대로 로그인 수단으로 쓰고, 여러 사이트가 로그인 상태를 공유하도록 만든 인증 서버입니다.
> 라이브러리 없이 **리다이렉트 + 첫 파티 세션 쿠키 + URL 해시 토큰 전달**만으로 SSO를 구성했습니다.

| | |
|---|---|
| **로그인 페이지** | https://api.sj-lab.co.kr/auth/login.html (**체험용 계정 버튼** 제공) |
| **스택** | Java 17 · Spring Boot 3.3.2 · Spring Cloud(Eureka) · jjwt 0.12.6(HS256) |
| **Eureka 이름** | `SJ-LAB-AUTHSERVER` (게이트웨이 경로 `/auth/**`) |

---

## 1. 왜 이렇게 만들었나

현장조사 앱과 QFieldCloud를 이미 쓰고 있어 **계정이 한 벌 더 생기는 것을 피하고 싶었습니다.** 그래서

- 회원가입·회원 테이블을 만들지 않고, 로그인 요청을 **QFieldCloud 로그인 API에 위임 검증**
- 검증에 성공하면 **이 서버가 서명한 sj-lab 전용 JWT**를 발급(QFieldCloud 토큰은 버림)
- 서비스들은 이 JWT만 신뢰하면 되므로, 나중에 다른 인증 수단이 추가돼도 영향 범위가 이 서버로 한정됩니다

---

## 2. SSO 동작 (라이브러리 없이)

```
1. 허브/지도 접속 → localStorage에 유효한 토큰이 없으면
   /auth/login.html?redirect_uri=<원래 주소> 로 이동
2. 로그인 페이지가 자기 오리진의 세션 쿠키로 GET /auth/session 확인
     있으면 → 폼 없이 새 토큰 발급 → 3번
     없으면 → 로그인 폼 → POST /auth/login (성공 시 세션 쿠키도 발급)
3. redirect_uri 로 되돌아가며 URL 해시(#auth_token=...)에 토큰 전달
4. 각 사이트의 게이트 스크립트가 해시에서 토큰을 꺼내 자기 localStorage에 저장하고 해시 제거
```

**설계 포인트**: 세션 쿠키(`sj_session`)는 **로그인 페이지 자신의 오리진에만** 존재합니다. 각 사이트는 3단계의 해시로 받은 토큰을 각자 저장하므로, 크로스 도메인 쿠키·`SameSite`·서브도메인 공유 문제를 아예 만들지 않았습니다. 쿠키는 "이미 로그인했는지" 확인하는 최상위 탐색용이라 `SameSite=Lax`로 충분합니다.

---

## 3. 면접에서 봐주셨으면 하는 부분

### ① 안전하지 않은 설정으로는 기동하지 않게

`JwtService`가 `@PostConstruct`에서 서명 키를 검증합니다. **운영 프로파일인데 기본 시크릿이거나 32바이트 미만이면 기동 자체를 막습니다.** 저장소가 public이라 "Secret 주입을 깜빡한 배포"가 조용히 성공하는 것이 가장 위험하다고 판단했고, 실제로 배포 시 `CreateContainerConfigError`로 바로 드러났습니다.

### ② 로그아웃이 안 되던 문제 — 상태가 두 군데 있었다

프론트 로그아웃이 `localStorage`만 지우고 로그인 페이지로 보냈더니, **살아 있는 세션 쿠키로 즉시 새 토큰이 발급되어** 로그아웃이 되지 않았습니다. 로그아웃 시 `logout=1`을 붙여 보내고, 로그인 페이지가 세션 확인 대신 `POST /auth/logout`으로 **쿠키부터 지운 뒤** 폼을 보여주도록 수정했습니다.

### ③ Spring Security 기본 로그아웃 필터 충돌

`POST /auth/logout`이 우리 컨트롤러 대신 시큐리티 기본 로그아웃 필터에 가로채여 `/login?logout`으로 리다이렉트됐습니다(겉으로는 405처럼 보임). `.logout(AbstractHttpConfigurer::disable)`로 명시적으로 꺼서 해결했고, 같은 함정을 문서에 남겼습니다.

### ④ 체험 계정을 서버로 옮긴 이유

포트폴리오 방문자용 "체험용 계정으로 로그인" 버튼을 처음에는 페이지 JS에 아이디·비밀번호를 담아 구현했는데, **소스 보기와 public 저장소에 그대로 노출**됐습니다. `POST /auth/login/demo` 엔드포인트로 옮겨 계정 정보를 서버 환경변수(k8s Secret)에서만 읽도록 바꾸고, 노출됐던 비밀번호는 교체했습니다. Secret이 없으면 **데모 버튼만 503**이고 일반 로그인은 정상 동작합니다.

### ⑤ 오픈 리다이렉트 차단

`redirect_uri`는 허용 오리진 목록(`localhost:3000`, `localhost:4000`, `sj-lab.co.kr`, `www.sj-lab.co.kr`)에 있을 때만 받아들입니다. 새 프론트 도메인을 추가하면 이 목록도 함께 고쳐야 한다는 점을 문서에 적어 두었습니다.

---

## 4. API

| 엔드포인트 | 설명 |
|---|---|
| `GET /auth/login.html` | 공유 로그인 페이지(SSO 진입점). 로딩 표시·체험 계정 버튼 포함 |
| `POST /auth/login` | `{username, password}` → QFieldCloud 위임 검증 → JWT + 세션 쿠키 |
| `POST /auth/login/demo` | 체험 계정 로그인(계정 정보는 서버에만) |
| `GET /auth/session` | 세션 쿠키로 로그인 상태 확인 + 토큰 재발급(리프레시 성격) |
| `POST /auth/logout` | 세션 쿠키 삭제 |
| `GET /auth/me` | `Authorization: Bearer` 토큰 유효성 확인 |

응답: `{accessToken, tokenType: "Bearer", expiresIn, username}` — 기본 만료 12시간.
오류 매핑: 자격 증명 오류 401 / 외부 API 장애 502 / 데모 계정 미설정 503.

---

## 5. 실행

```bash
mvnw.cmd clean package                                     # target/sj-lab-authserver.jar
mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local   # local 프로파일 필수(Eureka localhost:8761)
mvnw.cmd test
```

로컬에서는 **비밀값 없이도 일반 로그인이 됩니다**(JWT 키는 local 프로파일 전용 기본값, 계정 검증은 입력한 QFieldCloud 계정). 체험 계정 버튼만 `AUTH_DEMO_*`가 필요하며, 총괄 저장소의 `scripts/local-stack.ps1`이 git 제외 파일에서 읽어 주입합니다.

| 환경변수 | 용도 | 필수 |
|---|---|---|
| `AUTH_JWT_SECRET` | JWT 서명 키(HS256, 32바이트 이상) | **운영 필수** — 없으면 기동 실패 |
| `AUTH_JWT_EXPIRATION_SECONDS` | 만료(초), 기본 43200 | 선택 |
| `AUTH_DEMO_USERNAME` / `AUTH_DEMO_PASSWORD` | 체험 계정 | 선택(없으면 데모 버튼만 503) |

---

## 6. 배포

```
git push → Jenkins(빌드 → 이미지 push) → sj-lab-k8s-manifests 의 image.tag 자동 커밋
        → ArgoCD 동기화 → Kubernetes 롤아웃
```

Secret: `auth-jwt-secret`(필수), `auth-demo-credentials`(선택). 이름·용도·확인 명령은 총괄 저장소의 `docs/k8s-secrets.md`에 정리돼 있습니다(값은 기록하지 않음).

---

## 7. 현재 범위와 한계 (의도적으로 남겨 둔 것)

- **백엔드 API에 토큰 검증을 강제하지 않습니다.** 지금은 화면 접근을 막는 UX 게이트까지이며, 진짜 보안 경계는 게이트웨이 전역 JWT 필터로 만들어야 합니다.
- **single-logout 없음**: 한 사이트에서 로그아웃해도 다른 사이트는 토큰 만료(12시간)까지 유지됩니다.
- 역할·권한 개념이 없고(`username`만 다룸), 리프레시 토큰 대신 `GET /session`이 그 역할을 합니다.
- **`/auth/login`에 레이트 리미팅이 없습니다.** 요청이 QFieldCloud 로그인 API로 그대로 전달되므로, 외부 노출 범위를 넓히기 전에 IP·계정 단위 제한이 필요합니다.
- 테스트는 컨텍스트 로딩 스모크뿐입니다.

## 참고

- SSO 흐름·설계 결정 상세: 이 저장소의 `CLAUDE.md`
- 전체 구조: 총괄 저장소 `mapservice-rest`의 `docs/system-architecture.md`
