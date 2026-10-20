# P0 — 환경 구축

- 기간: 2026-10-19
- 커밋: `54c7599` → `86f255c` → `a6f28e4`
- 결과: **완료** (완료 조건 2개 모두 확인)

## 1. 목표와 완료 조건

SPEC 12절 P0:

| 완료 조건 | 결과 | 확인 방법 |
| --- | --- | --- |
| `docker compose up` 후 Grafana에서 앱 JVM·HikariCP·MySQL·Redis 지표가 보임 | ✅ | Grafana `/api/ds/query`로 패널 쿼리를 직접 실행. 최근 5분 데이터 포인트 40~60개 확인 |
| `./gradlew test` 통과 (컨텍스트 로드 테스트) | ✅ | `--rerun-tasks`로 재실행. app 1건, mock-external 1건 통과 |

## 2. 환경

| 항목 | 값 |
| --- | --- |
| 머신 | MacBook Air, Apple M4 (10코어), 메모리 16GB, macOS 26.5 |
| Docker | Docker Desktop, 서버 29.4.1 / VM 10 CPU, 메모리 약 7.75GiB |
| JDK | 기본 JDK는 25.0.1이고, Gradle toolchain으로 Temurin 21.0.12 사용 |
| 빌드 | Gradle 9.8.1 (wrapper) |
| k6 | v2.3.0 |
| Python | 3.14.5 |

## 3. 결정 사항

SPEC 0절의 `[결정 필요]` 항목을 확정하고 SPEC에 반영했다.

| 항목 | 결정 | 이유 |
| --- | --- | --- |
| 빌드 | Gradle Kotlin DSL, 멀티모듈 (`app`, `mock-external`) | SPEC 기본값 |
| 루트 패키지 | `io.github.songjunpyo.subsidy` (mock-external은 `.mock`) | GitHub ID 기반 (SPEC 권장안) |
| Spring Boot | 4.1.1 | 2026-10-19 기준 start.spring.io의 최신 GA. `resilience4j-spring-boot4` 2.4.0이 있어 SPEC의 "호환 안 되면 아래 버전" 조건에 해당하지 않음 |

SPEC에 없어서 정한 것:

| 항목 | 결정 | 이유 |
| --- | --- | --- |
| Prometheus 스크랩 주기 | 5초 | 기본 15초는 10~20분짜리 부하 테스트를 보기엔 너무 거침 |
| 메트릭 공통 태그 | `application=subsidy-app` | Grafana에서 앱 지표를 구분하기 위함 (Phase 8 앱 2대 대비) |
| Tomcat MBean 등록 | 켬 (`server.tomcat.mbeanregistry.enabled`) | `tomcat_threads_*` 지표가 있어야 P7 스레드 고갈을 관찰할 수 있음 |
| `loader` 프로필 | 웹 서버 없이 실행 | 적재만 하고 끝나는 용도 |
| mock-external 스크랩 | 하지 않음 | SPEC 2절 수집 대상에 없음. 필요해지면 P7에서 추가 |
| 로컬 계정 | 평문 (`subsidy`, `exporter`, Grafana `admin`) | 로컬 개발용 |

**의도적으로 그대로 둔 것**: HikariCP 풀 크기는 기본값 10. 병목 4(P6)의 기준선 조건이다.

## 4. 구성 결과

### 모듈

| 모듈 | 포트 | 내용 |
| --- | --- | --- |
| `app` | 8082 | Actuator(`health`, `prometheus`), JPA·Redis·Flyway 의존성, 프로필 `local`(기본) / `loadtest` / `loader` |
| `mock-external` | 8081 | 빈 Spring Boot 앱 (API는 P2) |

### 인프라 (`infra/docker-compose.yml`)

| 서비스 | 이미지 | 포트 | 설정 |
| --- | --- | --- | --- |
| MySQL | `mysql:8.4` (8.4.11) | 3306 | utf8mb4, `local_infile=ON`, `slow_query_log=ON`, `long_query_time=0.2` |
| Redis | `redis:7.4` (7.4.11) | 6379 | 영속화 끔 (`save ""`, `appendonly no`) |
| Prometheus | `prom/prometheus:v3.15.0` | 9090 | `--web.enable-remote-write-receiver` |
| Grafana | `grafana/grafana:13.2.4` | 3000 | 데이터소스·대시보드 provisioning |
| mysqld_exporter | `prom/mysqld-exporter:v0.20.0` | 9104 | 전용 계정 `exporter` (PROCESS, REPLICATION CLIENT, SELECT) |
| redis_exporter | `oliver006/redis_exporter:v1.93.0` | 9121 | |
| node_exporter | `prom/node-exporter:v1.12.1` | 9100 | Docker VM 지표 |

### Grafana 대시보드 `Subsidy Rush` (`infra/grafana/dashboards/subsidy.json`)

SPEC 8절 행 순서대로 7개 행, 패널 28개.

| 행 | 패널 | 현재 상태 |
| --- | --- | --- |
| 비즈니스 | 신청 결과별 처리량, 예산 잔액, 대기열 길이, 대사 불일치 | 쿼리만 넣어 둔 자리. P2부터 값이 나옴 |
| API (RED) | 요청률, 5xx 에러율, p95·p99 | ✅ |
| JVM·HikariCP·Tomcat·서킷브레이커 | Hikari 커넥션·획득 시간, Tomcat 스레드, Heap, GC, 서킷브레이커 | ✅ (서킷브레이커는 P7부터) |
| MySQL | QPS, 스레드, InnoDB 행 락 대기, slow query | ✅ |
| Redis | 명령 처리량, 클라이언트, 메모리, hit/miss | ✅ |
| 서버 | CPU, 메모리, load | ✅ |
| k6 | VU, 요청률, p95·p99, 실패율 | k6 실행 중에만 값이 나옴 (지표 이름 확인 완료) |

## 5. 문제와 해결

| # | 문제 | 원인 | 해결 |
| --- | --- | --- | --- |
| 1 | CLAUDE.md가 SPEC.md와 함께 `docs/`로 옮겨짐 | 수동 이동 | 루트로 되돌림. Claude Code는 루트의 CLAUDE.md만 자동으로 읽음 |
| 2 | 커밋 작성자가 `...@songjunpyoui-MacBookAir.local`로 기록되어 잔디에 반영 안 됨 | git `user.email` 미설정 | 전역 설정 후 기존 커밋 작성자를 다시 쓰고 `--force-with-lease`로 push (파일 내용은 그대로임을 tree 해시로 확인) |
| 3 | MySQL 컨테이너 시작 실패 `bind: address already in use` | Homebrew MySQL이 로그인 시 자동 실행되며 3306을 점유 | `brew services stop mysql`. 다시 쓸 때는 `brew services start mysql` (이 프로젝트와 동시 실행 불가) |
| 4 | node-exporter 시작 실패 `path / is mounted on / but it is not a shared or slave mount` | Docker Desktop(macOS)이 `rslave` 마운트 전파를 지원하지 않음 | `/:/host:ro`로 변경. GCP(Linux) 이전 시 `rslave` 복원 |
| 5 | mysqld_exporter 계정이 자동으로 만들어지지 않음 | `docker-entrypoint-initdb.d` 스크립트는 볼륨이 처음 만들어질 때만 실행되는데, 볼륨이 이미 있었음 | 같은 스크립트를 수동으로 1회 실행 (`IF NOT EXISTS`라 재실행 안전) |
| 6 | `tomcat_threads_*` 지표 없음 | Boot 기본값에서 Tomcat MBean 등록이 꺼져 있음 | `server.tomcat.mbeanregistry.enabled: true` |
| 7 | 5xx 에러율 패널이 5xx가 없을 때 0%가 아니라 빈 그래프 | 5xx 시계열이 없으면 분자가 비어 결과가 사라짐 | 분자에 `or vector(0)` 추가. 요청이 아예 없는 구간은 0/0이라 비어 보이는 게 정상 |
| 8 | Spring Initializr가 500 반환 | `bootVersion=4.1.1.RELEASE` 형식을 받지 않음 | 파라미터를 빼고 기본값(4.1.1)으로 생성 |
| 9 | 앱 포트 8080이 다른 프로젝트와 충돌 (2026-10-20) | 다른 프로젝트가 8080을 사용 | 앱을 8082로 변경 (8081은 mock-external). `application.yml`, `prometheus.yml`, SPEC 2절, README 반영 |

## 6. 검증 기록

| 항목 | 기대 | 관찰 |
| --- | --- | --- |
| Java toolchain | Java 21 바이트코드 | class 파일 major version 65 |
| Testcontainers 사용 여부 | compose DB가 아닌 임시 컨테이너 | 테스트 로그 `jdbc:mysql://localhost:62289/test`, `mysql:8.4`·`redis:7.4` 컨테이너 시작 |
| Flyway 동작 | 마이그레이션 없음 경고 | `No migrations found` (V1은 P2) |
| Prometheus targets | 5개 UP | app, mysql, redis, node, prometheus 모두 UP |
| exporter 실제 접속 | `*_up = 1` | `mysql_up` 1, `redis_up` 1 |
| HikariCP 기본값 | 최대 10 | `hikaricp_connections_max` 10 |
| Tomcat 스레드 | 지표 노출 | busy 1, current 10, max 200 |
| remote write 수신 | 404가 아님 | 빈 POST에 400 (수신 경로 켜짐) |
| k6 지표 이름 (v2.3) | 대시보드 쿼리와 일치 | `k6_vus`, `k6_http_reqs_total`, `k6_http_req_duration_p95/p99`, `k6_http_req_failed_rate` 확인 |
| k6 응답시간 단위 | 초 | p95 0.0021 (health 엔드포인트) |
| node_exporter 범위 | Docker VM | `MemTotal` 8,321,712,128 bytes (맥 본체 16GB가 아님) |

## 7. 다음 Phase로 넘기는 것

- **MySQL `time_zone`이 `SYSTEM`(컨테이너 UTC)이다.** SPEC은 앱 타임존을 `Asia/Seoul`로 정하고 있으니, 시간 데이터를 처음 쓰는 P2 전에 JDBC 설정을 맞춘다.
- **`loader` 프로필의 JDBC URL에 적재용 옵션이 아직 없다.** `rewriteBatchedStatements=true`와 `allowLoadLocalInfile=true`는 P1에서 추가한다(SPEC 9.4).
- **Grafana 로그에 `alerting` provisioning 디렉터리가 없다는 에러가 나온다.** 알림을 쓰지 않아서 생기는 것이라 동작에는 영향이 없다.
- **CLAUDE.md의 "현재 단계" 줄을 Phase 1로 바꿔야 한다.** 규칙상 사람이 바꾼다.
