# 실행·종료 가이드

모든 명령은 프로젝트 루트(`subsidy-rush/`)에서 실행합니다.

## 켜기

1. **Docker Desktop 실행**: 메뉴 막대에 고래 아이콘이 보이면 준비된 상태입니다.
2. **인프라 실행** (MySQL, Redis, Prometheus, Grafana, exporter)
   ```bash
   docker compose -f infra/docker-compose.yml up -d
   ```
3. **앱 실행**: 터미널 하나를 계속 차지합니다.
   ```bash
   ./gradlew :app:bootRun
   ```
   로그에 `Started SubsidyApplication`이 보이면 준비된 상태입니다.

`mock-external`(:8081)은 Phase 2부터 필요합니다. 그때는 터미널을 하나 더 열어 `./gradlew :mock-external:bootRun`을 실행합니다.

## 상태 확인

```bash
docker compose -f infra/docker-compose.yml ps    # 컨테이너 7개가 Up인지
curl localhost:8082/actuator/health              # {"status":"UP"} 이면 정상
```

| 화면 | 주소 |
| --- | --- |
| Grafana | http://localhost:3000/d/subsidy (admin / admin) |
| Prometheus 수집 대상 | http://localhost:9090/targets (5개 모두 UP) |

## 끄기

1. **앱 끄기**: `bootRun`을 실행한 터미널에서 `Ctrl + C`
2. **인프라 끄기**
   ```bash
   docker compose -f infra/docker-compose.yml stop
   ```
   데이터는 그대로 남습니다. Docker Desktop을 종료(Quit)해도 같은 효과입니다.

## 주의

- **`docker compose ... down -v`는 쓰지 않습니다.** `-v`가 MySQL·Prometheus·Grafana 데이터를 모두 지웁니다.
- **맥을 재시작하거나 Docker Desktop을 껐다 켜면 컨테이너는 자동으로 올라오지 않습니다.** "켜기" 2번부터 다시 하면 됩니다.

## 자주 만나는 문제

| 증상 | 원인 | 해결 |
| --- | --- | --- |
| `bind: address already in use` (3306) | Homebrew MySQL이 켜져 있음 | `brew services stop mysql` |
| 앱 시작 실패 `Communications link failure` | MySQL 컨테이너가 꺼져 있음 | "켜기" 2번 실행 후 앱 재시작 |
| 앱 시작 실패 `Port 8082 was already in use` | 앱이 이미 떠 있음 | `kill $(lsof -tiTCP:8082 -sTCP:LISTEN)` 후 재시작 |
| Grafana 앱 패널이 비어 있음 | 앱이 꺼져 있음 | "켜기" 3번 |
