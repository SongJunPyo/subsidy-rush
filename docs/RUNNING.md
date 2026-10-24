# 실행·종료 가이드

모든 명령은 프로젝트 루트(`subsidy-rush/`)에서 실행합니다.

## 처음 한 번만: 비밀번호 파일 만들기

비밀번호는 커밋하지 않는 `infra/.env`에 둡니다. 견본을 복사하고 값을 채웁니다.

```bash
cp infra/.env.example infra/.env   # 그다음 infra/.env를 열어 4개 값을 채운다
```

| 변수 | 계정 | 권한 | 쓰는 곳 |
| --- | --- | --- | --- |
| `MYSQL_ROOT_PASSWORD` | MySQL `root` | 서버 전체 관리자. 모든 DB, 계정 생성, 권한 부여 | 컨테이너 상태 확인, exporter 계정을 만드는 초기화 스크립트 |
| `MYSQL_PASSWORD` | MySQL `subsidy` | `subsidy` DB만 | 앱, 적재기, 사람이 직접 접속할 때 |
| `MYSQLD_EXPORTER_PASSWORD` | MySQL `exporter` | 서버 상태 읽기만 | mysqld-exporter (Prometheus 수집) |
| `GF_SECURITY_ADMIN_PASSWORD` | Grafana `admin` | Grafana 관리자 | 브라우저 로그인 |

용도마다 필요한 권한만 가진 계정을 따로 둡니다. 앱 비밀번호가 새도 서버 전체가 넘어가지 않게 하려는 것입니다. 데이터를 보는 용도라면 `subsidy` 계정이면 충분합니다.

비밀번호는 임시값이 아니라 무작위로 만든 고정값입니다. 혼자 로컬에서 개발하는 동안은 그대로 써도 됩니다. GCP에 올릴 때(P8)는 새 값을 따로 만듭니다.

이 값들은 MySQL·Grafana 볼륨이 **처음 만들어질 때만** 적용됩니다. 나중에 바꾸려면 볼륨을 지우고 다시 만들어야 하며, 그러면 DB 데이터도 사라집니다(명단은 `load-data`로 1분 안에 다시 넣을 수 있습니다).

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
| Grafana | http://localhost:3000/d/subsidy (`admin` / `infra/.env`의 `GF_SECURITY_ADMIN_PASSWORD`) |
| Prometheus 수집 대상 | http://localhost:9090/targets (5개 모두 UP) |

## DB 직접 보기

H2 같은 메모리 DB가 아니라 Docker 안의 MySQL이고, 데이터는 볼륨(`mysql-data`)에 저장되어 껐다 켜도 남아 있습니다. 인프라가 켜진 상태에서 MySQL용 도구로 접속합니다.

| 항목 | 값 |
| --- | --- |
| Host / Port | `localhost` / `3306` |
| Database | `subsidy` |
| User | `subsidy` |
| Password | `infra/.env`의 `MYSQL_PASSWORD` |

- **IntelliJ Ultimate**: Database 탭 → `+` → Data Source → MySQL
- **DBeaver**(무료), **MySQL Workbench**: 새 연결 → MySQL. `Public Key Retrieval is not allowed`가 나오면 드라이버 속성에서 `allowPublicKeyRetrieval=true`
- **터미널**: `docker compose -f infra/docker-compose.yml exec mysql mysql -usubsidy -p subsidy` (비밀번호는 프롬프트에 입력)

```sql
SELECT COUNT(*) FROM eligible_person;          -- P1 적재 후 1000000
SELECT * FROM eligible_person LIMIT 10;
SELECT target_group, COUNT(*), SUM(support_amount) FROM eligible_person GROUP BY target_group;
```

테이블과 컬럼의 의미는 SPEC 4절, CSV 파일 위치는 SPEC 9.5절에 있습니다.

## 끄기

1. **앱 끄기**: `bootRun`을 실행한 터미널에서 `Ctrl + C`
2. **인프라 끄기**
   ```bash
   docker compose -f infra/docker-compose.yml stop
   ```
   데이터는 그대로 남습니다. Docker Desktop을 종료(Quit)해도 같은 효과입니다.

## 주의

- **`docker compose ... down -v`는 쓰지 않습니다.** `-v`가 MySQL·Prometheus·Grafana 데이터를 모두 지웁니다.
- **포트는 같은 네트워크의 다른 기기에도 열려 있습니다.** `3306:3306`처럼 적혀 있어 맥 밖에서도 접속할 수 있고, Redis(6379)는 비밀번호가 없습니다. 공용 와이파이에서 작업할 때는 docker-compose의 포트를 `127.0.0.1:3306:3306` 형식으로 바꾸는 것을 고려합니다.
- **`infra/.env`는 커밋하지 않습니다.** `.gitignore`에 들어 있습니다.
- **맥을 재시작하거나 Docker Desktop을 껐다 켜면 컨테이너는 자동으로 올라오지 않습니다.** "켜기" 2번부터 다시 하면 됩니다.

## 자주 만나는 문제

| 증상 | 원인 | 해결 |
| --- | --- | --- |
| `bind: address already in use` (3306) | Homebrew MySQL이 켜져 있음 | `brew services stop mysql` |
| 앱 시작 실패 `Communications link failure` | MySQL 컨테이너가 꺼져 있음 | "켜기" 2번 실행 후 앱 재시작 |
| 앱 시작 실패 `Port 8082 was already in use` | 앱이 이미 떠 있음 | `kill $(lsof -tiTCP:8082 -sTCP:LISTEN)` 후 재시작 |
| Grafana 앱 패널이 비어 있음 | 앱이 꺼져 있음 | "켜기" 3번 |
| `required variable ... infra/.env 필요` | `infra/.env`가 없음 | "처음 한 번만" 절 |
| 앱 시작 실패 `Access denied for user 'subsidy'` | `infra/.env`의 `MYSQL_PASSWORD`가 볼륨을 만들 때 값과 다름 | 값을 되돌리거나 볼륨을 다시 만든다 |
