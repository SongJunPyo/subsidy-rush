# subsidy-rush

가상의 '고유가 피해 지원금' 선착순 신청 시스템.
일부러 허술한 기준선에서 시작해 병목 7개를 하나씩 **재현 → 측정 → 해결 → 재측정**하는 학습·포트폴리오 프로젝트입니다.

> 실제 서비스가 아닌 **축소 모델**입니다. 수치는 이 저장소의 환경에서 측정한 값이며, 실제 규모의 성능을 뜻하지 않습니다.

## 병목별 요약

| # | 병목 | 문제 | 원인 | 해결 | 수치 (before → after) |
| --- | --- | --- | --- | --- | --- |
| b1 | 중복 신청 | | | | |
| b2 | 예산 초과 지급 | | | | |
| b3 | 조회 풀스캔 | | | | |
| b4 | 핫 로우 락 경합 · 커넥션 풀 고갈 | | | | |
| b5 | 외부 API 지연 | | | | |
| b6 | 오픈 스파이크 | | | | |
| b7 | 장애 중 정합성 | | | | |

측정 결과 상세: [`docs/results/`](docs/results/)

## 아키텍처

```
[k6] ──HTTP──> [app :8082] ──┬──> [MySQL 8.4]
                             ├──> [Redis 7]
                             └──> [mock-external :8081]
[Prometheus :9090] <── app, mysqld_exporter, redis_exporter, node_exporter, k6(remote write)
[Grafana :3000] ──> Prometheus
```

## 금융권 시스템 구조와의 대응

은행의 일반적인 흐름(채널 → MCA → 계정계 → FEP → 외부기관)을 이 프로젝트의 구성요소에 대응시켰습니다. 실제 구조는 은행·사업마다 다르며, 여기서는 일반 구조를 단순화했습니다.

| 금융권 구조 | 역할 | 이 프로젝트에서 해당하는 부분 | 관련 병목 |
| --- | --- | --- | --- |
| 채널 (앱·웹) | 사용자 요청의 출발점 | k6 | |
| MCA | 여러 채널의 요청을 받아 계정계로 넘기는 관문, 유입 통제 | Nginx `limit_req` + 대기열 (P8) | P8 오픈 스파이크 |
| 계정계 | 돈과 원장을 다루는 핵심 시스템, 서비스 로직 | app + MySQL (+ Redis) | P3~P6 중복·예산·풀스캔·락 경합 |
| FEP | 외부기관과 전문을 주고받는 대외 관문 | app의 외부 호출 부분 (타임아웃·서킷브레이커·벌크헤드) | P7 외부 API 지연 |
| 외부기관 (정부 등) | 자격·계좌 검증, 지급 처리 | mock-external | P7 장애 주입 |
| 대외기관과의 대사 | 양쪽 기록을 맞춰 보는 작업 | 대사 배치 (P9) | P9 장애 중 정합성 |

MCA·FEP는 별도 서버로 만들지 않았습니다. 직접 만든 미들웨어는 실제 상용 제품의 특성을 갖지 않아 측정 의미가 약하고, 한 머신에서 구성요소가 늘면 측정값이 섞이기 때문입니다. 대신 대외 연계의 핵심 문제인 **응답 미수신**(외부기관은 처리했는데 응답을 못 받은 상황)과 **외부 구간 지연**을 해당 Phase(P7·P9)에서 다룹니다.

## 기술 스택

Java 21, Spring Boot 4.1.1, Gradle Kotlin DSL 멀티모듈 / MySQL 8.4, Redis 7.4, Flyway, JPA·JdbcTemplate·MyBatis / k6, Micrometer, Prometheus, Grafana

## 실행 방법

사전 준비: JDK 21, Docker, k6, 비밀번호 파일 (`cp infra/.env.example infra/.env` 후 값 채우기. 커밋하지 않음)

```bash
docker compose -f infra/docker-compose.yml up -d   # 인프라 (MySQL, Redis, Prometheus, Grafana, exporter)
./gradlew :app:bootRun                             # 앱 (:8082, 프로필 local)
./gradlew :mock-external:bootRun                   # 가짜 외부 API (:8081)
./gradlew test                                     # 테스트 (Testcontainers, Docker 필요)
```

가상 데이터 생성과 적재 (Python 3, 최초 1회 가상환경 준비):

```bash
python3 -m venv tools/datagen/.venv && tools/datagen/.venv/bin/pip install -r tools/datagen/requirements.txt
tools/datagen/.venv/bin/python tools/datagen/generate.py --rows 1000000 --participants 120000 --seed 42 --out data/
./gradlew :app:bootRun --args="--spring.profiles.active=loader --mode=load-data --file=$PWD/data/eligible_person.csv"
```

| 서비스 | 주소 |
| --- | --- |
| Grafana | http://localhost:3000 (`admin` / `infra/.env`의 비밀번호), 대시보드 `Subsidy Rush` |
| Prometheus | http://localhost:9090 |
| 앱 지표 | http://localhost:8082/actuator/prometheus |

## 설계상 단순화한 부분

- **인증 생략**: 실제로는 본인인증 후 서버가 CI를 얻지만, 이 프로젝트에서는 클라이언트가 `ciHash`를 요청 본문으로 보냅니다.
- **외래 키 없음**: FK가 자동으로 인덱스를 만들어 병목 실험 조건이 흐려지므로 두지 않습니다.
- **로컬 서버 지표**: Docker Desktop(macOS)의 node_exporter는 맥 본체가 아니라 Docker VM의 지표를 수집합니다. 로컬 측정은 기능 확인용이며, 공식 비교는 GCP 환경에서 합니다.
