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

## 기술 스택

Java 21, Spring Boot 4.1.1, Gradle Kotlin DSL 멀티모듈 / MySQL 8.4, Redis 7.4, Flyway / k6, Micrometer, Prometheus, Grafana

## 실행 방법

사전 준비: JDK 21, Docker, k6

```bash
docker compose -f infra/docker-compose.yml up -d   # 인프라 (MySQL, Redis, Prometheus, Grafana, exporter)
./gradlew :app:bootRun                             # 앱 (:8082, 프로필 local)
./gradlew :mock-external:bootRun                   # 가짜 외부 API (:8081)
./gradlew test                                     # 테스트 (Testcontainers, Docker 필요)
```

| 서비스 | 주소 |
| --- | --- |
| Grafana | http://localhost:3000 (admin / admin), 대시보드 `Subsidy Rush` |
| Prometheus | http://localhost:9090 |
| 앱 지표 | http://localhost:8082/actuator/prometheus |

## 설계상 단순화한 부분

- **인증 생략**: 실제로는 본인인증 후 서버가 CI를 얻지만, 이 프로젝트에서는 클라이언트가 `ciHash`를 요청 본문으로 보냅니다.
- **외래 키 없음**: FK가 자동으로 인덱스를 만들어 병목 실험 조건이 흐려지므로 두지 않습니다.
- **로컬 서버 지표**: Docker Desktop(macOS)의 node_exporter는 맥 본체가 아니라 Docker VM의 지표를 수집합니다. 로컬 측정은 기능 확인용이며, 공식 비교는 GCP 환경에서 합니다.
