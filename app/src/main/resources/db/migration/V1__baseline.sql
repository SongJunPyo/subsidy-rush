-- SPEC 4절 V1. 인덱스·UNIQUE·FK가 없는 것은 의도된 기준선이다 (병목 1·3)
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
