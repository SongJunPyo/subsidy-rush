# SPEC — 고유가 피해 지원금 선착순 신청 시스템

구현의 기준 문서다. `DESIGN.md`와 다르면 이 문서를 따른다. 여기 없는 결정은 임의로 정하지 말고 질문한다.

---

## 0. 결정 사항

`[결정 필요]` 표시는 기본값으로 진행하되, 사람이 바꿀 수 있는 항목이다.

| 항목 | 값 | 비고 |
| --- | --- | --- |
| 빌드 | Gradle Kotlin DSL, 멀티모듈(`app`, `mock-external`) | 결정됨 |
| 루트 패키지 | `io.github.songjunpyo.subsidy` (mock-external은 `.mock`) | 결정됨 (GitHub ID 기반) |
| Java | 21 | |
| Spring Boot | 프로젝트 생성 시점의 최신 GA 버전을 `build.gradle.kts`에 고정하고 여기에 적는다: `4.1.1` (Gradle 9.8.1) | 결정됨 (2026-10-19). Resilience4j는 `resilience4j-spring-boot4` 2.4.0 사용 가능 확인 |
| DB | MySQL 8.4, InnoDB, utf8mb4 | |
| 캐시·대기열 | Redis 7.x, 클라이언트 Lettuce(Spring 기본) | |
| 마이그레이션 | Flyway (`flyway-mysql` 모듈 포함) | |
| 실행 방식 | 개발 중에는 인프라만 Docker Compose, 앱은 `bootRun` | 측정 시에는 앱도 컨테이너로 (Phase 8부터 GCP) |
| 인증 | 생략. 클라이언트가 `ciHash`를 요청 본문으로 보낸다 | 실제로는 본인인증 후 서버가 CI를 얻는다. README에 명시 |
| 금액 | 원 단위 `long` / `BIGINT` | 소수점 없음 |
| 시간 | DB는 `DATETIME(6)`, 앱 타임존 `Asia/Seoul`, API는 ISO-8601 오프셋 포함 | |

---

## 1. 도메인 규칙

- **대상자 명단**(`eligible_person`)에 있는 사람만 신청할 수 있다. 명단은 이벤트 중 바뀌지 않는다.
- **1인 1회**: 한 사람(`person_id`)에게 승인된 신청은 최대 1건이다.
- **선착순 예산**: 승인 금액 합계는 총예산을 넘을 수 없다. 잔액이 지원금보다 적으면 `BUDGET_EXHAUSTED`. 부분 지급은 없다.
- **지원 금액**: 대상군별 고정 금액 (9절 데이터 표).
- **5부제** (설정 `subsidy.weekday-rotation.enabled`, 기본 `false`): 출생연도 끝자리 1·6 월, 2·7 화, 3·8 수, 4·9 목, 5·0 금. 주말은 누구나 신청 가능. 해당 요일이 아니면 `NOT_APPLY_DAY`.
- **검증**: 신청 시 본인인증 API와 계좌 검증 API를 모두 통과해야 한다 (Mock).
- **재신청**: 승인된 사람은 재신청할 수 없다. 검증 실패나 예산 소진으로 거절된 요청은 신청 행을 만들지 않는다.

---

## 2. 시스템 구성

```
[k6] ──HTTP──> [Nginx (Phase 8~)] ──> [app ×1 (Phase 8부터 ×2)] ──┬──> [MySQL 8.4]
                                                                  ├──> [Redis 7]
                                                                  └──> [mock-external :8081]
[Prometheus] <── scrape: app /actuator/prometheus, mysqld_exporter, redis_exporter, node_exporter
             <── remote write: k6
[Grafana] ──> Prometheus
[대사 배치 (Phase 9~)] ──> MySQL, Redis
```

### 로컬 포트

| 서비스 | 포트 | 비고 |
| --- | --- | --- |
| app | 8082 | 8080은 다른 프로젝트가 사용해서 변경 (2026-10-20) |
| mock-external | 8081 | 앱과 별도 프로세스 (지연이 앱 스레드를 공유하지 않게) |
| MySQL | 3306 | `local_infile=ON`, `slow_query_log=ON`, `long_query_time=0.2` |
| Redis | 6379 | Phase 9 전까지 영속화 끔 |
| Prometheus | 9090 | `--web.enable-remote-write-receiver` |
| Grafana | 3000 | 데이터소스·대시보드 provisioning |
| mysqld_exporter / redis_exporter / node_exporter | 9104 / 9121 / 9100 | |
| Nginx | 80 | Phase 8부터 |

---

## 3. API 명세

공통: 기본 경로 `/api/v1`, JSON, UTF-8. 요청에 `X-Request-Id`가 없으면 서버가 생성해 응답 헤더와 로그에 넣는다.
개인정보(`ciHash`, 계좌번호)는 URL에 넣지 않는다 (경로·쿼리 금지, 본문으로만).

### 3.1 자격 조회 — `POST /api/v1/eligibility/inquiry`

```json
// 요청
{ "ciHash": "64자리 hex" }

// 200
{
  "eligible": true,
  "personId": 123456,
  "targetGroup": "TRUCK",
  "supportAmount": 300000,
  "applyWeekday": "MONDAY",
  "applicableToday": true
}
```

명단에 없으면 404 `NOT_ELIGIBLE`.

### 3.2 신청 — `POST /api/v1/applications`

헤더: `Idempotency-Key: <UUID v4>` — 기준선(Phase 2)에서는 받기만 하고 무시한다(의도적 결함). Phase 3부터 필수, 없으면 400 `IDEMPOTENCY_KEY_REQUIRED`.
Phase 8부터 `X-Admission-Token` 필수, 없거나 만료되면 403 `ADMISSION_REQUIRED`.

```json
// 요청
{ "ciHash": "64자리 hex", "bankCode": "004", "accountNumber": "110-1234-567890" }

// 201
{
  "applicationId": 1001,
  "status": "APPROVED",
  "amount": 300000,
  "appliedAt": "2026-10-20T09:00:00.123+09:00"
}
```

**멱등성 동작 (Phase 3~)**

| 상황 | 응답 |
| --- | --- |
| 처음 보는 키 | 정상 처리, 결과(상태코드+본문)를 키에 저장 |
| 같은 키 + 같은 본문, 처리 완료 | 저장된 응답을 그대로 반환 + 헤더 `Idempotent-Replayed: true` |
| 같은 키 + 같은 본문, 처리 중 | 409 `REQUEST_IN_PROGRESS` |
| 같은 키 + 다른 본문 | 422 `IDEMPOTENCY_KEY_MISMATCH` |

본문 비교는 요청 본문의 SHA-256 해시로 한다. 거절 응답(409·422 등)도 저장해 재전송 시 같은 결과를 준다. 단 5xx는 저장하지 않는다 (재시도 허용).

### 3.3 신청 조회 — `GET /api/v1/applications/{applicationId}`

200 `{ applicationId, status, amount, appliedAt, updatedAt }` / 404 `APPLICATION_NOT_FOUND`.
(실제 서비스라면 본인 확인이 필요하지만 인증 생략 범위다.)

### 3.4 예산 조회 — `GET /api/v1/budget`

200 `{ "totalAmount": 0, "remainingAmount": 0, "approvedCount": 0 }`. 지역 분할 모드(Phase 6)에서는 `regions` 배열을 추가한다.

### 3.5 대기열 (Phase 8~)

- `POST /api/v1/queue/enter` — 요청 `{ "ciHash": "..." }` → 200 `{ "queueToken": "uuid", "position": 1234 }`
- `GET /api/v1/queue/status?token={queueToken}` → 200 `{ "status": "WAITING", "position": 1200 }` 또는 `{ "status": "ADMITTED", "admissionToken": "uuid", "expiresInSec": 300 }`

`queueToken`은 개인정보가 아니므로 쿼리에 둬도 된다.

### 3.6 관리자 (프로필 `loadtest`에서만 활성화)

- `POST /admin/reset` — 요청 `{ "totalAmount": 1000000000, "budgetMode": "SINGLE" }` (`SINGLE` | `REGIONAL`)
  `application`(P9부터는 `outbox_event`·`payment_ledger`도)을 비우고, 예산 행을 다시 만들고, Redis의 `subsidy:*` 키를 지운다. `eligible_person`은 건드리지 않는다.
- `GET /admin/verify` — 11.2절 불변식을 계산해 `{ "passed": true, "checks": [ { "name": "...", "expected": 0, "actual": 0, "passed": true } ] }` 반환. k6 `teardown()`에서 호출한다.

### 3.7 에러 코드

응답 본문: `{ "code": "ALREADY_APPLIED", "message": "이미 신청하셨습니다.", "requestId": "..." }`

| HTTP | code | 상황 | 도입 |
| --- | --- | --- | --- |
| 400 | `INVALID_REQUEST` | 형식 오류 | P2 |
| 400 | `IDEMPOTENCY_KEY_REQUIRED` | 키 누락 | P3 |
| 403 | `NOT_APPLY_DAY` | 5부제 요일 아님 | P2 |
| 403 | `ADMISSION_REQUIRED` | 입장 토큰 없음·만료 | P8 |
| 404 | `NOT_ELIGIBLE` | 명단에 없음 | P2 |
| 404 | `APPLICATION_NOT_FOUND` | 신청 없음 | P2 |
| 409 | `ALREADY_APPLIED` | 이미 승인된 신청 있음 | P2 |
| 409 | `BUDGET_EXHAUSTED` | 잔액 부족 | P2 |
| 409 | `REQUEST_IN_PROGRESS` | 같은 키 처리 중 | P3 |
| 422 | `IDEMPOTENCY_KEY_MISMATCH` | 같은 키, 다른 본문 | P3 |
| 422 | `VERIFICATION_FAILED` | 본인인증·계좌 검증 실패 | P2 |
| 429 | `RATE_LIMITED` | Nginx 제한 | P8 |
| 503 | `TEMPORARILY_BUSY` | 낙관적 락 재시도 소진 | P4 |
| 503 | `EXTERNAL_UNAVAILABLE` | 외부 API 서킷 open·타임아웃 | P7 |
| 500 | `INTERNAL_ERROR` | 그 외 | P2 |

---

## 4. DB 스키마와 마이그레이션 계획

`eligible_person` 데이터는 Flyway가 아니라 적재 도구(Phase 1)로 넣는다. FK는 두지 않는다 (FK가 자동으로 인덱스를 만들어 병목 실험 조건이 흐려지므로). 이 결정은 README에 적는다.

### V1__baseline.sql (Phase 2)

```sql
CREATE TABLE eligible_person (
  person_id       BIGINT       NOT NULL,
  ci_hash         CHAR(64)     NOT NULL,  -- 의도적으로 인덱스 없음 (병목 3)
  name            VARCHAR(30)  NOT NULL,
  birth_year      SMALLINT     NOT NULL,
  region_code     CHAR(2)      NOT NULL,
  target_group    VARCHAR(20)  NOT NULL,
  support_amount  BIGINT       NOT NULL,
  created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (person_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE support_budget (
  budget_id         BIGINT       NOT NULL AUTO_INCREMENT,
  region_code       CHAR(2)      NULL,     -- NULL = 전국 단일 예산
  total_amount      BIGINT       NOT NULL,
  remaining_amount  BIGINT       NOT NULL,
  version           BIGINT       NOT NULL DEFAULT 0,
  updated_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (budget_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE application (
  application_id         BIGINT       NOT NULL AUTO_INCREMENT,
  person_id              BIGINT       NOT NULL,  -- 의도적으로 UNIQUE 없음 (병목 1)
  idempotency_key        CHAR(36)     NULL,
  budget_id              BIGINT       NOT NULL,
  amount                 BIGINT       NOT NULL,
  status                 VARCHAR(20)  NOT NULL,  -- APPROVED, PAID, CANCELLED
  bank_code              CHAR(3)      NOT NULL,
  account_number_masked  VARCHAR(30)  NOT NULL,
  created_at             DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at             DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (application_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 이후 마이그레이션

| 버전 | Phase | 내용 |
| --- | --- | --- |
| V2__application_unique.sql | P3 | `UNIQUE uk_application_person (person_id)`, `UNIQUE uk_application_idem (idempotency_key)` |
| V3__eligible_ci_index.sql | P5 | `UNIQUE uk_eligible_ci (ci_hash)` — 커버링 인덱스 비교 실험은 수동 SQL로 하고, 최종 채택안만 마이그레이션에 넣는다 |
| V4__outbox_ledger.sql | P9 | `outbox_event(event_id, aggregate_type, aggregate_id, event_type, payload JSON, status, created_at, published_at)`, `payment_ledger(ledger_id, application_id UNIQUE, amount, paid_at)` |

---

## 5. 기준선(Phase 2)의 신청 처리 흐름 — 의도적 결함 포함

이 순서와 결함을 **그대로** 구현한다. 각 결함 옆의 Phase에서 고친다.

1. `ci_hash`로 `eligible_person` 조회 — 인덱스 없음 → 풀스캔 **(P5)**
2. 5부제 확인 (설정이 켜져 있을 때만)
3. `person_id`로 기존 신청 존재 여부 `SELECT` → 있으면 409 — 조회 후 저장(check-then-act), UNIQUE 없음 **(P3)**
4. `@Transactional` 안에서 mock 본인인증 → 계좌 검증을 **동기 호출, 타임아웃 설정 없음** **(P7)**
5. 예산 행을 JPA로 읽고, 자바에서 `remaining - amount`를 계산해 저장 — 락·버전 체크 없음 → Lost Update **(P4)**, 모든 요청이 한 행에 몰림 **(P6)**
6. `application` INSERT (`status = APPROVED`)
7. HikariCP 풀 크기는 기본값 그대로 **(P6)**

---

## 6. Redis 키 설계

접두사는 모두 `subsidy:`. `/admin/reset`이 이 접두사의 키를 모두 지운다.

| 키 | 타입 | 값 | TTL | 도입 |
| --- | --- | --- | --- | --- |
| `subsidy:idem:{idempotencyKey}` | String(JSON) | `{ "state": "IN_PROGRESS"\|"DONE", "requestHash", "httpStatus", "body" }` | 24시간 | P3 |
| `subsidy:elig:{ciHash}` | String(JSON) | 3.1의 응답 | 없음 (명단 불변) | P5 |
| `subsidy:budget:{budgetId}` | String(정수) | 잔액(원) | 없음 | P6 |
| `subsidy:queue:waiting` | ZSET | member=`queueToken`, score=진입 시각(ms) | 없음 | P8 |
| `subsidy:queue:token:{queueToken}` | String | 대기 중인 사람의 `person_id` | 30분 | P8 |
| `subsidy:admit:{admissionToken}` | String | `person_id` | 5분 | P8 |

### 멱등키 처리 (P3)

`SET subsidy:idem:{key} {"state":"IN_PROGRESS",...} NX EX 86400` 성공 시에만 처리한다. 처리 끝나면 `DONE` + 응답으로 덮어쓴다. 처리 중 예외(5xx)면 키를 지워 재시도를 허용한다.

### 예산 차감 Lua (P6)

```lua
-- KEYS[1] = subsidy:budget:{budgetId}, ARGV[1] = amount
local remaining = tonumber(redis.call('GET', KEYS[1]))
if remaining == nil then return -2 end          -- 키 없음 (초기화 누락)
local amount = tonumber(ARGV[1])
if remaining < amount then return -1 end        -- 잔액 부족
return redis.call('DECRBY', KEYS[1], amount)    -- 차감 후 잔액
```

- `/admin/reset` 때 DB 예산으로 Redis 키를 초기화한다.
- Lua 차감 후 DB INSERT가 실패하면 즉시 `INCRBY`로 보상한다.
- 이 모드에서 DB `support_budget.remaining_amount`는 요청마다 갱신하지 않는다 (그러면 핫 로우가 그대로 남음). 이벤트 중 잔액의 기준은 Redis이고, `application` 테이블이 원장이다. DB 잔액은 대사 배치(P9)가 맞춘다.
- 보상하지 못한 경우(앱 강제 종료 등)는 P9에서 다룬다.

### 대기열 입장 (P8)

설정 `subsidy.queue.admit-per-second`만큼 매초 `ZPOPMIN`으로 꺼내 `admissionToken`을 발급한다. `ZPOPMIN`은 원자적이므로 앱이 2대여도 중복 입장이 생기지 않지만, 두 대가 모두 꺼내면 초당 입장 수가 2배가 된다. 그래서 각 인스턴스가 `admit-per-second / 인스턴스 수`만큼 꺼내거나, Redis 락으로 한 대만 실행하게 한다. 둘 중 하나를 고르고 이유를 결과 문서에 적는다.

---

## 7. Mock 외부 API (`mock-external`, 포트 8081)

| API | 요청 | 응답 |
| --- | --- | --- |
| `POST /mock/identity/verify` | `{ "ciHash" }` | 200 `{ "verified": true }` |
| `POST /mock/account/verify` | `{ "bankCode", "accountNumber" }` | 200 `{ "valid": true }` — 계좌번호가 `0000`으로 끝나면 `false` |
| `PUT /mock/admin/fault` | `{ "latencyMs": 2000, "jitterMs": 500, "failureRate": 0.3, "failureStatus": 503 }` | 현재 설정 반환 |
| `GET /mock/admin/fault` | | 현재 설정 |

기본값은 지연 0, 실패율 0. 지연은 요청 스레드에서 sleep으로 만든다 (mock 자체가 병목이 되지 않게 Tomcat 스레드 수를 넉넉히 설정).

---

## 8. 메트릭

`management.metrics.distribution.percentiles-histogram.http.server.requests=true`를 켜서 p95·p99를 Prometheus에서 계산한다.

| Micrometer 이름 | 타입 | 태그 | Prometheus에서 보이는 이름 |
| --- | --- | --- | --- |
| `subsidy.application.requests` | Counter | `result` = approved, duplicate, budget_exhausted, not_eligible, verification_failed, idempotent_replay, error | `subsidy_application_requests_total` |
| `subsidy.budget.remaining` | Gauge | `budget_id` | `subsidy_budget_remaining` |
| `subsidy.queue.waiting` | Gauge | | `subsidy_queue_waiting` |
| `subsidy.reconciliation.mismatch` | Gauge | `check` | `subsidy_reconciliation_mismatch` |

Grafana 대시보드(`infra/grafana/dashboards/subsidy.json`) 행 순서: 비즈니스 → API(RED) → JVM·HikariCP·Tomcat·서킷브레이커 → MySQL → Redis → 서버 → k6.

---

## 9. 가상 데이터 생성 (`tools/datagen/generate.py`)

- 의존성: `numpy`만 (`requirements.txt`). Faker 쓰지 않음. 100만 건 생성이 2분 안에 끝나야 한다.
- 인자: `--rows 1000000 --participants 120000 --seed 42 --out data/`
- 같은 seed면 항상 같은 결과가 나와야 한다 (재현성).
- `data/`는 `.gitignore`에 넣는다.

### 9.1 `data/eligible_person.csv`

헤더 포함. 열 순서: `person_id,ci_hash,name,birth_year,region_code,target_group,support_amount`

| 열 | 규칙 |
| --- | --- |
| person_id | 1부터 N까지 |
| ci_hash | `sha256(f"ci-{seed}-{person_id}")` hex |
| name | 성(상위 20개 성씨) + 이름 2음절 무작위 조합 |
| birth_year | 1945~2004 균등 분포 |
| region_code | 아래 지역 비율 |
| target_group, support_amount | 아래 대상군 표 |

**대상군**

| target_group | 의미 | 비율 | support_amount |
| --- | --- | --- | --- |
| `TRUCK` | 화물운수 종사자 | 30% | 300000 |
| `FARM_FISH` | 농어민 | 25% | 150000 |
| `LOW_INCOME` | 저소득 차량 소유자 | 25% | 100000 |
| `TAXI_BUS` | 택시·버스 종사자 | 20% | 200000 |

**지역** (시도 코드 2자리, 비율은 인구 비중 근사치)

| 코드 | 지역 | 비율 | 코드 | 지역 | 비율 |
| --- | --- | --- | --- | --- | --- |
| 11 | 서울 | 18.5% | 41 | 경기 | 26.6% |
| 26 | 부산 | 6.4% | 51 | 강원 | 2.9% |
| 27 | 대구 | 4.5% | 43 | 충북 | 3.1% |
| 28 | 인천 | 6.0% | 44 | 충남 | 4.1% |
| 29 | 광주 | 2.7% | 52 | 전북 | 3.4% |
| 30 | 대전 | 2.8% | 46 | 전남 | 3.5% |
| 31 | 울산 | 2.1% | 47 | 경북 | 4.9% |
| 36 | 세종 | 0.8% | 48 | 경남 | 6.4% |
| | | | 50 | 제주 | 1.3% |

### 9.2 `data/participants.csv` (k6 입력)

열: `ci_hash,eligible,dup_mode,repeat,bank_code,account_number`

- 90%: 명단에서 중복 없이 뽑은 사람 (`eligible=true`)
- 10%: 명단에 없는 해시 `sha256(f"none-{seed}-{i}")` (`eligible=false`)
- `dup_mode`: 95% `none`, 2.5% `same_key`, 2.5% `diff_key`. 중복인 경우 `repeat`는 2 또는 3 (반반), 아니면 1
- `bank_code`: 은행 코드 10개 중 무작위 / `account_number`: 가짜 번호, 1%는 `0000`으로 끝나게 (검증 실패용)

### 9.3 `data/summary.json`

대상군·지역별 인원, 총 지원금 수요(원), 권장 총예산(수요의 30%, 100만 원 단위 내림).

### 9.4 적재 (Phase 1)

`app`의 `loader` 프로필로 실행: `--mode=jpa|jdbc|load-data`

1. `jpa`: Spring Data `saveAll()`. `person_id`를 직접 넣는 엔티티라 `save()`가 INSERT 전에 SELECT를 하는 현상이 생기는데, 그대로 두고 관찰해 결과에 적는다
2. `jdbc`: `JdbcTemplate.batchUpdate`, 배치 크기 1000, JDBC URL에 `rewriteBatchedStatements=true`
3. `load-data`: `LOAD DATA LOCAL INFILE` (JDBC URL `allowLoadLocalInfile=true`, 서버 `local_infile=ON`)

각 방식 전에 테이블을 비우고 소요 시간을 잰다. `jpa`가 너무 오래 걸리면 10만 건으로 재고 그렇게 했다고 명시한다 (100만 건으로 환산한 추정값은 쓰지 않는다).

---

## 10. 부하 테스트 (`load-test/`)

- 공통 모듈 `lib/common.js`: `SharedArray`로 `participants.csv` 로드, 요청 헬퍼, `teardown()`에서 `/admin/verify` 호출 후 실패 시 테스트 실패 처리
- Prometheus 전송: `k6 run -o experimental-prometheus-rw script.js`, 환경변수 `K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write`, `K6_PROMETHEUS_RW_TREND_STATS=p(95),p(99),avg`
- 도착률·VU 수 같은 숫자는 스크립트 상단 상수나 환경변수로 뺀다. 목표 수치는 P2의 `load.js`로 찾은 한계 TPS를 기준으로 정한다 (미리 숫자를 정하지 않는다)

| 파일 | 내용 | Phase |
| --- | --- | --- |
| `smoke.js` | VU 1~5, 1분, 전 API 호출 | P2 |
| `duplicate.js` | `dup_mode`가 `same_key`면 같은 키로, `diff_key`면 다른 키로 `repeat`개 요청을 `http.batch`로 동시에 보냄. 나머지는 1회 | P3 |
| `budget-race.js` | 예산을 승인 약 1,000건분으로 리셋한 뒤, 서로 다른 eligible 참가자 수천 명이 동시에 신청 (`shared-iterations`) | P4 |
| `load.js` | `constant-arrival-rate`, 10~20분 | P2부터 계속 |
| `spike.js` | `ramping-arrival-rate`, 10초 안에 0 → 목표치 | P8 |
| `soak.js` | 중간 부하 30~60분 | P10 |
| `fault.js` | `load.js` 실행 중 `infra/scripts/chaos.sh`로 앱 kill·Redis 재시작 | P9 |

---

## 11. 측정 절차

### 11.1 순서

1. `POST /admin/reset` (테스트에 맞는 예산·모드)
2. 앱 재시작 → 1분 워밍업 (smoke 수준 부하)
3. 본 측정. **같은 조건으로 3회 반복**하고 중앙값 기록
4. `/admin/verify` 통과 확인 (k6 teardown이 자동으로)
5. Grafana에서 측정 구간의 RED 패널 + 원인 패널 캡처 → `docs/results/img/`
6. 환경 기록: 로컬/GCP, 머신 사양, 커밋 해시, 데이터 건수, 주요 설정(풀 크기, 전략 등)

로컬(MacBook) 측정값은 기능 확인용이다. 공식 before/after 비교는 같은 환경끼리만 한다.

### 11.2 불변식 (`/admin/verify`)

| 이름 | 계산 | 기대값 | 적용 |
| --- | --- | --- | --- |
| duplicate_approvals | `SELECT COUNT(*) - COUNT(DISTINCT person_id) FROM application WHERE status IN ('APPROVED','PAID')` | 0 | 항상 |
| overspent_amount | `GREATEST(승인 합계 - 총예산, 0)` | 0 | 항상 |
| budget_ledger_gap | `(총예산 - 잔액) - 승인 합계`. 잔액은 Redis 모드면 Redis 값, 아니면 DB 값 | 0 | 항상 (Redis 모드는 P9 전까지 장애 주입 테스트 제외) |
| negative_remaining | 잔액 < 0 인 예산 수 | 0 | 항상 |
| payment_ledger_gap | `SUM(payment_ledger.amount) - SUM(application.amount WHERE status='PAID')` | 0 | P9~ |

### 11.3 결과 템플릿 (`docs/results/bN-<이름>.md`)

```markdown
# bN — 제목

## 환경
로컬/GCP, 머신, 커밋, 데이터 건수, 주요 설정

## 문제 재현
사용한 테스트, 관찰된 현상, 증거(불변식 결과, 로그, 캡처)

## 측정 결과 (3회 중앙값)
| 지표 | before | 안 A | 안 B | 안 C |
| --- | --- | --- | --- | --- |
| TPS | | | | |
| p95 (ms) | | | | |
| p99 (ms) | | | | |
| 에러율 (%) | | | | |
| 불변식 | | | | |
| 원인 지표 (예: Hikari pending 최대) | | | | |

## 원인 분석
Grafana 캡처와 함께

## 선택한 해결안과 이유

## 트레이드오프와 남은 문제
```

---

## 12. Phase별 작업과 완료 조건

일정 기준: 주 15~20시간, 8주. `bN` = 병목 번호.

### P0 — 환경 구축 (1주차)

- Gradle 멀티모듈(`app`, `mock-external`), `.gitignore`, `README.md` 뼈대
- `app`: Actuator(`health`, `prometheus`) 노출, 프로필 `local` / `loadtest` / `loader`
- `infra/docker-compose.yml`: 2절 포트 표의 서비스(Nginx 제외), `infra/prometheus.yml`, Grafana 데이터소스·대시보드 provisioning
- Grafana 대시보드 초안 (8절 행 구성, 비즈니스 패널은 자리만)

**완료 조건**: `docker compose up` 후 Grafana에서 앱 JVM·HikariCP·MySQL·Redis 지표가 보임 / `./gradlew test` 통과(컨텍스트 로드 테스트)

### P1 — 데이터 생성·적재 (2주차)

- 9절의 생성 스크립트, `loader` 프로필 3가지 적재 방식
- `docs/results/p1-loading.md`

**완료 조건**: `eligible_person` 1,000,000건 / CSV 행 수와 DB 행 수 일치 / 3방식 소요 시간 기록

### P2 — 기준선 (2주차)

- 3절 API(대기열 제외), 3.6 관리자 API, 5절 흐름을 결함 포함 그대로
- Flyway V1, mock-external 7절
- `smoke.js`, `load.js`, `lib/common.js`
- 단일 스레드 통합 테스트(정상 신청, 명단 없음, 재신청 거절, 검증 실패)
- **재현 테스트**: 동시성 테스트로 중복 승인과 예산 초과가 실제로 발생함을 확인. 불변식을 검증하는 이 테스트들은 지금은 실패하므로 `@Tag("known-issue")`를 붙여 기본 테스트에서 제외하고, 해당 Phase에서 태그를 뗀다
- `load.js`로 한계 TPS를 찾아 기록 (이후 목표 수치의 기준)

**완료 조건**: smoke 통과 / 단일 스레드 테스트 통과 / 재현 테스트에서 중복·초과가 실제로 관찰됨 / `git tag baseline`

### P3 — 병목 1: 중복 신청 (3주차)

- 재현: `duplicate.js` + 같은 사람 3개 동시 요청 테스트
- 해결: V2 마이그레이션(UNIQUE) + 제약 위반을 409 `ALREADY_APPLIED`로 변환, 3.2절 멱등키 처리(6절 Redis)
- 측정: 멱등키 처리 전후의 p95 증가분

**완료 조건**: `duplicate.js` 후 `duplicate_approvals = 0` / `same_key` 중복은 `Idempotent-Replayed: true`로 같은 응답 / `diff_key` 중복은 409 / 중복 관련 known-issue 테스트 통과 / `b1-before`, `b1-after` 태그

### P4 — 병목 2: 예산 초과 지급 (3주차)

- 재현: `budget-race.js`
- 해결 3안을 설정 `subsidy.budget.strategy`로 전환 가능하게 모두 구현해 비교
  - `pessimistic`: `SELECT ... FOR UPDATE`
  - `optimistic`: `@Version` + 재시도 최대 5회(짧은 무작위 백오프), 소진 시 503 `TEMPORARILY_BUSY`
  - `atomic-update`: `UPDATE support_budget SET remaining_amount = remaining_amount - ? WHERE budget_id = ? AND remaining_amount >= ?`, 영향받은 행이 0이면 `BUDGET_EXHAUSTED`

**완료 조건**: 3안 모두 `overspent_amount = 0`, `budget_ledger_gap = 0` / TPS·p95·에러율 비교표 / 기본 전략 선택과 이유 / 태그

### P5 — 병목 3: 조회 풀스캔 (4주차)

- 재현: `load.js`에서 slow query log·`EXPLAIN` 결과 저장 (`type=ALL` 확인)
- 해결 단계별 비교: 인덱스 없음 → V3 인덱스 → 인덱스 + Redis 자격 캐시(cache-aside)

**완료 조건**: `EXPLAIN`이 인덱스를 사용 / 측정 구간 slow query 0건 / 3단계 비교표 / 태그

### P6 — 병목 4: 핫 로우 락 경합 + 커넥션 풀 고갈 (4주차)

- 재현: P4에서 고른 전략으로 `load.js` 고부하 → Hikari pending, InnoDB row lock wait 관찰
- 해결 비교
  - `REGIONAL`: 시도별 17행으로 예산 분할 (트레이드오프: 한 지역이 먼저 소진될 수 있음)
  - `redis-lua`: 6절 Lua 차감 (`subsidy.budget.strategy=redis-lua`)
  - 풀 크기 실험: 10 / 20 / 40을 측정해 결정 (공식으로 정하지 않음)

**완료 조건**: 채택안에서 Hikari pending 최대값이 before 대비 크게 감소(수치 기록) / 불변식 통과 / 비교표 / 태그

### P7 — 병목 5: 외부 API 지연 (5주차)

- 재현: mock에 지연 2000ms·실패율 30% 주입 → Tomcat 스레드 고갈, 상관없는 `GET /budget`까지 느려지는 것 확인
- 해결 단계별 적용·측정: 타임아웃 → 서킷브레이커 → 벌크헤드 → 외부 호출을 DB 트랜잭션 밖으로 이동. 타임아웃 값은 측정으로 정한다
- (선택) 검증을 접수 후 비동기 처리로 분리 — 하려면 상태 흐름 변경을 먼저 제안할 것

**완료 조건**: 장애 주입 중 `GET /budget` p95가 장애 없을 때와 비슷한 수준(수치 기록) / 서킷 open 시 503 `EXTERNAL_UNAVAILABLE`이 빠르게 반환 / 태그

### P8 — 병목 6: 오픈 스파이크 + GCP 이전 (6주차)

- GCP Compute Engine으로 이전 (VM 구성은 DESIGN.md). Terraform은 선택
- 재현: `spike.js` → 5xx 급증
- 해결: 3.5절 대기열 + 입장 토큰, Nginx `limit_req`, 앱 2대, 5부제 on/off 비교

**완료 조건**: spike 중 5xx 비율 before/after 기록 / 대기열 길이 메트릭이 Grafana에 보임 / 불변식 통과 / 태그

### P9 — 병목 7: 장애 중 정합성 (7주차)

- 재현: `fault.js`로 처리 도중 앱 kill, Redis 재시작 → `budget_ledger_gap ≠ 0` 관찰
- 해결
  - Transactional Outbox: 신청 INSERT와 `outbox_event`를 같은 트랜잭션에 저장 → 발행기가 지급 처리 → `payment_ledger` 기록, `PAID`로 변경
  - Redis 차감 보상: 실패 시 `INCRBY`, 보상하지 못한 건은 대사가 복구. 차감 근거를 남기는 방식은 설계안을 먼저 제안하고 승인받은 뒤 구현
  - Redis 영속화(AOF) 켠 경우와 끈 경우 비교
  - Spring Batch 대사 작업: 11.2 불변식 계산 → `subsidy_reconciliation_mismatch` 갱신 → 불일치 보고. 자동 보정은 설정으로 켜고 끌 수 있게, 보정 내역은 별도 기록

**완료 조건**: 장애 주입 후 대사가 불일치를 탐지 / 복구 후 모든 불변식 0 / 태그

### P10 — 최종 측정·정리 (8주차)

- GCP에서 전 시나리오 재실행(`soak.js` 포함), 결과 문서 갱신
- `README.md`: 병목별 "문제 → 원인 → 해결 → 수치" 요약표, 아키텍처, 실행 방법, 축소 모델임을 명시

**완료 조건**: 모든 결과 파일이 실제 측정값으로 채워짐 / README 완성
