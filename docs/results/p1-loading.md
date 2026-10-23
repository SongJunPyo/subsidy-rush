# P1 — 데이터 생성·적재

- 기간: 2026-10-20
- 기준 커밋: `c40c30c` + P1 작업 중 미커밋 변경 (측정 당시 코드는 P1 커밋에 그대로 들어감)
- 결과: **완료** (완료 조건 3개 모두 확인)

## 1. 목표와 완료 조건

SPEC 12절 P1:

| 완료 조건 | 결과 | 확인 방법 |
| --- | --- | --- |
| `eligible_person` 1,000,000건 | ✅ | `COUNT(*)` 1,000,000, `person_id` 1~1,000,000, `ci_hash` 중복 없음 |
| CSV 행 수와 DB 행 수 일치 | ✅ | 측정 15회 모두 `csvRows = writtenRows = dbRows` |
| 5방식 소요 시간 기록 | ✅ | 아래 3절 (jpa는 10만 건) |

## 2. 결정 사항

| 항목 | 결정 | 이유 |
| --- | --- | --- |
| Flyway V1 시점 | P2 → P1으로 당김 (DDL은 SPEC 그대로) | 적재기가 `eligible_person` 테이블을 필요로 함. 스키마는 Flyway로만 만든다는 규칙(CLAUDE.md 3) 유지 |
| 적재 방식 | SPEC 3가지 + MyBatis 2가지 = 5가지 | 금융권에서 많이 쓰는 SQL 매퍼 방식과 비교. MyBatis 안에서도 BATCH와 `<foreach>`를 나눠 봄 |
| 권장 예산 기준 | 신청자 중 명단에 있는 사람의 지원금 합 × 30% | 명단 전체 기준이면 부하 테스트 중 예산이 소진되지 않음 |
| jpa 측정 건수 | 10만 건 3회 | 10만 건 1회가 71.3초. 100만 건 1회가 기준(10분)을 넘을 것으로 판단 |
| 측정 범위 | 파일 읽기부터 마지막 커밋까지 | `load-data`는 드라이버가 파일을 직접 보내므로, 나머지도 CSV 파싱을 포함해야 공정함 |
| 커밋 단위 | 1000건마다 1커밋 (`load-data`만 1문장) | SPEC의 jdbc 배치 크기 1000에 맞춤 |
| 생성 데이터 세부 | 이름: 상위 20개 성씨 × 이름 음절 40개 중 2개 / 은행 코드 10개 / 계좌 `XXX-XXXX-XXXXXX` / 신청자 순서는 섞음 | SPEC에 없는 세부. 명단 밖 사람이 k6 입력 앞부분에도 고르게 섞이도록 순서를 섞음 |

## 3. 측정 결과

### 환경

| 항목 | 값 |
| --- | --- |
| 머신 | MacBook Air, Apple M4 (10코어), 16GB, macOS 26.5 / Docker VM 10 CPU, 약 7.75GiB |
| MySQL | 8.4.11 컨테이너, 설정 기본값: `innodb_buffer_pool_size` 128MiB, `log_bin` ON, `sync_binlog` 1, `innodb_flush_log_at_trx_commit` 1 |
| 앱 | Spring Boot 4.1.1, HikariCP 기본(최대 10), `rewriteBatchedStatements=true`, `allowLoadLocalInfile=true` |
| 데이터 | `eligible_person.csv` 1,000,000행 (101MB), seed 42 |
| 측정 시각 | 2026-10-20 17:13~17:24, 방식별로 연속 실행, 매회 `TRUNCATE` 후 시작 |

### 결과 (3회 중앙값)

| 방식 | 건수 | 소요 시간 | 3회 측정값 (초) | 처리량 (건/초) | `Com_select` | `Com_insert` | `Questions` |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `jpa` | **100,000** | 69.5초 | 62.9 / 69.5 / 71.3 | 1,439 | 100,043 | 100,000 | 200,572 |
| `jdbc` | 1,000,000 | 24.0초 | 23.5 / 24.0 / 24.9 | 41,746 | 1,017 | 1,000 | 2,106 |
| `mybatis-batch` | 1,000,000 | 31.7초 | 30.7 / 31.7 / 32.6 | 31,559 | 1,018 | 1,000 | 2,121 |
| `mybatis-foreach` | 1,000,000 | 55.5초 | 55.3 / 55.5 / 55.7 | 18,020 | 1,033 | 1,000 | 2,221 |
| `load-data` | 1,000,000 | 6.0초 | 5.6 / 6.0 / 7.5 | 167,308 | 3 | 0 | 22 |

- `Com_select`·`Com_insert`·`Questions`는 적재 전후 MySQL 전역 카운터의 차이예요. exporter 등 다른 접속의 쿼리도 조금 섞여 있어요(초당 약 4건).
- **`jpa`는 10만 건이라 소요 시간을 다른 방식과 직접 비교할 수 없어요.** 처리량(건/초)은 참고만 하세요. 테이블이 커질수록 INSERT 비용이 달라질 수 있어서, 건수가 다르면 조건도 다른 거예요.
- 원본 기록: `data/load-results.jsonl` (커밋하지 않음, 15줄)

### 관찰한 현상

1. **JPA는 한 건마다 SELECT를 먼저 보낸다.** 10만 건 적재에 `Com_select`가 100,043, `Com_insert`가 100,000이에요. `person_id`를 직접 넣는 엔티티라 `save()`가 새 데이터인지 판단하지 못하고, `merge`가 SELECT로 먼저 확인해요. SPEC 9.4에서 고치지 말고 관찰하라고 한 현상이에요. 통합 테스트(`jpa는_INSERT_전에_건마다_SELECT를_한다`)로도 고정해 두었어요.
2. **`jdbc`·MyBatis 두 방식은 100만 건이 INSERT 문장 1,000개로 갔다.** `Com_insert`가 1,000이에요. `rewriteBatchedStatements=true`가 1000건짜리 배치를 여러 행 INSERT 한 문장으로 바꿔 보낸 결과예요. `mybatis-foreach`는 처음부터 1000건을 한 문장으로 만들어서 같은 1,000이에요.
3. **서버로 간 문장 수는 같은데 `mybatis-foreach`가 `jdbc`보다 2.3배 느리다.** 차이는 서버가 아니라 앱(클라이언트) 쪽에서 생긴 것으로 보여요. 원인은 아직 확인하지 않은 가설이에요. 가설은 MyBatis가 1000건짜리 동적 SQL을 만들고, 문장마다 파라미터 7,000개를 리플렉션으로 꺼내 바인딩하는 비용이에요.
4. **`mybatis-batch`는 `jdbc`보다 32% 느리다.** 서버로 간 문장 수가 같으니, 이것도 MyBatis가 한 건마다 문장을 처리하는 비용으로 보여요(가설, 미확인).
5. **`load-data`가 가장 빠르다.** `jdbc`보다 4배 빨라요. `Com_insert`가 0인 건 `LOAD DATA`가 INSERT가 아니라 별도 명령(`Com_load`)으로 집계되기 때문이에요. 첫 회만 7.5초로 느렸는데(나머지 2회 5.6·6.0초), 파일 캐시 같은 예열 효과로 보여요(미확인).
6. **`jdbc`·MyBatis에서 1000건 묶음마다 SELECT가 약 1건씩 나간다** (`Com_select` 약 1,017). 누가 보내는지는 확인하지 않았어요.

### 해석과 선택

- **명단 적재처럼 파일을 통째로 넣는 작업은 `load-data`가 맞다.** 다시 적재해야 할 때(볼륨 재생성 등)도 이 방식을 써요.
- **애플리케이션 코드에서 대량 INSERT가 필요하면 `jdbc` 배치 > `mybatis-batch` > `mybatis-foreach` 순이다.** MyBatis를 쓴다면 `<foreach>`보다 BATCH 실행기가 낫다는 걸 수치로 확인했어요.
- **JPA `saveAll()`을 대량 적재에 쓰면 안 된다.** ID를 직접 넣는 엔티티면 건마다 SELECT가 추가돼요. 단건 처리용으로만 써요(CLAUDE.md 기술 스택 방침과 같음).

### 캡처

적재기는 웹 서버 없이 도는 별도 프로세스라 Prometheus가 수집하지 않아요. Grafana에서는 MySQL·서버 행만 측정 구간을 보여 줘요. 측정 중 캡처는 하지 않았고, 아래 링크로 해당 구간을 다시 볼 수 있어요(Prometheus 보관 기간 15일 안).

http://localhost:3000/d/subsidy/subsidy-rush?from=1792483920000&to=1792484700000

## 4. 생성 데이터 (`tools/datagen/generate.py`)

| 항목 | 결과 |
| --- | --- |
| 생성 시간 | 2.1초 (100만 + 12만 건, 기준 2분) / 최대 메모리 약 784MiB |
| 재현성 | 같은 seed로 두 번 생성하면 파일 3개의 SHA-256이 같음 |
| 검증 테스트 | `unittest` 15개 통과 (행 수, `ci_hash` 공식, 비율 ±0.5%p, 중복 없는 추출, 계좌 `0000` 1%, 재현성 등) |
| 신청자 | 120,000명 (명단 안 108,000 / 밖 12,000), `dup_mode` none 113,933 · same_key 3,049 · diff_key 3,018 |
| 명단 전체 지원금 | 192,528,950,000원 |
| 신청자 수요 | 20,752,300,000원 |
| **권장 총예산** | **6,225,000,000원** (수요의 30%, 100만 원 단위 내림) |

## 5. 문제와 해결

| # | 문제 | 원인 | 해결 |
| --- | --- | --- | --- |
| 1 | SPEC상 V1은 P2인데 P1 적재기에 테이블이 필요 | SPEC Phase 배치 충돌 | V1 전체를 P1으로 당김 (SPEC 4절·12절 수정) |
| 2 | 상대 경로 `data/`로는 적재 파일을 못 찾음 | `bootRun`의 실행 위치가 `app/` 폴더 | `--file`은 절대 경로만 받게 함 (`$PWD/data/...`) |
| 3 | 테스트에서 `rewriteBatchedStatements` 등이 빠짐 | Testcontainers가 만든 JDBC URL에는 loader용 파라미터가 없음 | URL 대신 `hikari.data-source-properties`에 같은 Connector/J 속성을 둠 |
| 4 | 공개 저장소에 로컬 DB·Grafana 비밀번호가 평문으로 있음 | P0에서 로컬용이라 평문으로 둠 | 비밀번호를 `infra/.env`(커밋 안 함, 견본 `infra/.env.example`)로 분리하고 새 값으로 교체. MySQL·Grafana 볼륨을 다시 만들고 명단은 `load-data`로 재적재. 옛 비밀번호 4개가 모두 거부되는 것 확인 |
| 5 | MySQL 초기화 실패 `01-exporter-user.sh: Permission denied` | macOS Docker Desktop 파일 공유에서는 실행 권한 없는 파일도 root에게 실행 가능(`-x`)으로 보여, 이미지가 source 대신 직접 실행을 시도함 | 스크립트에 shebang과 실행 권한(755)을 주고 `mysql` 클라이언트를 직접 호출. Linux에서도 같은 방식으로 동작 |

## 6. 검증 기록

| 항목 | 기대 | 관찰 |
| --- | --- | --- |
| 적재기 통합 테스트 | 5방식 모두 2,500건 적재, 한글 이름 유지 | 9개 통과 (방식별 5 + 재적재, JPA SELECT, `--rows`, `load-data` 제한) |
| 전체 테스트 | 통과 | Java 11개 (app 10, mock-external 1), Python 15개 |
| Flyway V1 | 테이블 3개, 인덱스·UNIQUE 없음 | `eligible_person` 인덱스는 PRIMARY 하나 |
| `ci_hash` 조회 실행 계획 | 풀스캔 (P5 기준선) | `EXPLAIN` `type=ALL`, `rows` 993,148 |
| 한글 저장 | CSV와 같음 | `person_id` 1 → `이형성` (CSV 1행과 같음) |
| 비밀번호 분리 | 옛 비밀번호 거부, 새 비밀번호 통과 | MySQL root·subsidy·exporter, Grafana admin 모두 확인. 커밋 대상 파일 61개에 새 비밀번호 없음 |

## 7. 다음 Phase로 넘기는 것

- **P2 부하 테스트 입력이 준비됐다:** `data/participants.csv` 120,000명, 권장 예산 6,225,000,000원.
- **MyBatis 사용 범위와 계층 구조(Component-Dbio 등)는 P2에서 API를 만들 때 정한다.**
- **묶음마다 나가는 SELECT 1건의 정체는 확인하지 않았다.** 필요하면 general log로 확인한다.
- **MySQL `time_zone`은 여전히 `SYSTEM`(UTC)이다.** P2에서 시간 데이터를 처음 쓰기 전에 맞춘다(P0에서 넘어온 항목).
