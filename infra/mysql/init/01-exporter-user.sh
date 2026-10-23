#!/bin/bash
# mysqld_exporter 전용 계정 (볼륨이 처음 만들어질 때만 실행된다)
# 비밀번호는 컨테이너 환경변수(infra/.env)에서 받는다. 실행 권한이 있어야 한다 (macOS Docker Desktop은
# 권한이 없어도 실행 가능으로 보여 source 대신 직접 실행을 시도한다)
set -euo pipefail
mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}" <<EOSQL
CREATE USER IF NOT EXISTS 'exporter'@'%' IDENTIFIED BY '${MYSQLD_EXPORTER_PASSWORD}' WITH MAX_USER_CONNECTIONS 3;
GRANT PROCESS, REPLICATION CLIENT, SELECT ON *.* TO 'exporter'@'%';
EOSQL
