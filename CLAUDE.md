# CLAUDE.md

가상의 '고유가 피해 지원금' 선착순 신청 시스템. **일부러 허술한 기준선(baseline)에서 시작해 병목 7개를 하나씩 재현 → 측정 → 해결 → 재측정**하는 학습·포트폴리오 프로젝트다.

- 구현 명세: `docs/SPEC.md` (API, DDL, Redis 키, 단계별 완료 조건) — **구현할 때 이 문서가 기준**
- 배경 설계: `docs/DESIGN.md` (있다면 참고용. SPEC.md와 다르면 SPEC.md를 따른다)
- 측정 결과: `docs/results/`

## 현재 단계

> **Phase 0 — 환경 구축** (끝나면 사람이 이 줄을 다음 Phase로 바꾼다)

현재 단계에 해당하는 작업만 한다. 다음 단계 작업은 요청받기 전까지 하지 않는다.

## 가장 중요한 규칙

1. **허술함은 의도된 것이다.** 기준선 코드(Phase 2)는 SPEC.md의 "의도적 결함"대로 만든다. 인덱스·UNIQUE 추가, 락 적용, 캐시 도입, 비동기화 등은 해당 병목 Phase가 오기 전까지 하지 않는다. "더 좋은 방법"이 보여도 먼저 고치지 말고 제안만 한다.
2. **병목 Phase는 항상 4단계로 진행한다.**
   1) 문제 재현: 테스트나 k6로 문제가 실제로 발생함을 보인다 (예: 중복 지급이 실제로 생김)
   2) 측정: SPEC.md의 측정 절차대로 수치 기록 → `git tag bN-before`
   3) 해결: SPEC.md에 적힌 해결안(비교 실험이 있으면 모두) 구현
   4) 재측정: 같은 조건으로 측정, 결과 파일 작성 → `git tag bN-after`
3. **DB 스키마 변경은 Flyway 마이그레이션 파일로만** 한다. 기존 마이그레이션 파일은 수정하지 않고 새 버전을 추가한다. Phase별로 어떤 버전이 들어가는지는 SPEC.md를 따른다.
4. **돈은 `long`(원 단위 정수)으로만 다룬다.** `double`/`float` 금지. DB는 `BIGINT`.
5. **정합성 검증이 성능보다 우선이다.** 모든 부하 테스트 뒤에 SPEC.md의 검증 쿼리를 실행하고, 하나라도 실패하면 그 결과는 "실패"로 기록한다.
6. 확실하지 않은 기술 선택이나 SPEC.md에 없는 결정은 임의로 정하지 말고 질문한다.

## 기술 스택

- Java 21, Spring Boot (버전은 SPEC.md "결정 사항"에 고정), Gradle Kotlin DSL 멀티모듈
- MySQL 8.4, Redis 7.x, Flyway
- JPA(조회·단건 처리) + JdbcTemplate(대량 처리·원자적 UPDATE)
- Resilience4j, Spring Batch(Phase 9부터)
- 테스트: JUnit 5, Testcontainers(MySQL, Redis), AssertJ
- 부하: k6 / 관측: Micrometer, Prometheus, Grafana, mysqld_exporter, redis_exporter, node_exporter

## 저장소 구조

```
app/              신청 시스템 (Spring Boot)
mock-external/    가짜 외부 API (본인인증·계좌 검증, 지연·장애 주입)
tools/datagen/    가상 데이터 생성 (Python)
load-test/        k6 시나리오
infra/            docker-compose.yml, prometheus.yml, grafana provisioning
docs/             SPEC.md, DESIGN.md, results/
```

`app` 패키지: `api`(컨트롤러·DTO) / `application`(서비스) / `domain`(엔티티·도메인 규칙) / `infra`(DB·Redis·외부 연동) / `support`(공통: 에러, 메트릭)

## 자주 쓰는 명령

```bash
docker compose -f infra/docker-compose.yml up -d      # 전체 인프라
./gradlew :app:bootRun                               # 앱 실행
./gradlew test                                       # 전체 테스트 (Testcontainers)
python tools/datagen/generate.py --rows 1000000      # 데이터 생성
k6 run load-test/<scenario>.js                       # 부하 테스트
```

명령이 바뀌거나 추가되면 이 섹션을 갱신한다.

## 코드 규칙

- 엔티티에 `@Data`, `@Setter` 금지. 상태 변경은 의도가 드러나는 메서드로 (`approve()`, `cancel()`)
- 트랜잭션 경계는 서비스 계층에서만 (`@Transactional`). 외부 API 호출을 트랜잭션 안에 두는 것은 **기준선의 의도적 결함**이며, Phase 7에서 바로잡는다
- 에러 응답은 SPEC.md의 에러 코드 표를 따른다. 새 코드가 필요하면 SPEC.md에 먼저 추가하자고 제안한다
- 동시성 테스트: `ExecutorService` + `CountDownLatch`로 동시에 출발시키고, 끝난 뒤 SPEC.md의 불변식(중복 0, 초과 0)을 검증한다
- 커스텀 메트릭 이름은 SPEC.md의 메트릭 표를 따른다
- 로그에 CI 해시·계좌번호 원문을 남기지 않는다 (마스킹)
- 커밋 메시지: `[Phase N] 요약` 형식, 한국어

## 측정 결과 기록

병목 Phase마다 `docs/results/bN-<이름>.md`를 SPEC.md의 결과 템플릿으로 작성한다. 수치는 실제 측정값만 적는다. 측정하지 않은 값을 추정해서 채우지 않는다.
