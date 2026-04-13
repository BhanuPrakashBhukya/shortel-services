CREATE DATABASE IF NOT EXISTS shortel;
USE shortel;

-- ── Users ──────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS users (
  id            BIGINT        NOT NULL AUTO_INCREMENT,
  email         VARCHAR(255)  NOT NULL,
  password_hash VARCHAR(60)   NOT NULL,    -- BCrypt cost-10 hash
  name          VARCHAR(255),
  role          VARCHAR(50)   NOT NULL DEFAULT 'USER',
  tenant_id     BIGINT,                    -- FK to tenants (nullable until tenant is created)
  is_active     TINYINT(1)    DEFAULT 1,
  created_at    DATETIME      DEFAULT NOW(),
  PRIMARY KEY (id),
  UNIQUE KEY uq_email (email),
  INDEX idx_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


CREATE TABLE IF NOT EXISTS tenants (
  id           BIGINT        NOT NULL AUTO_INCREMENT,
  name         VARCHAR(255)  NOT NULL,
  email        VARCHAR(255)  NOT NULL,
  plan         ENUM('FREE','PAID') DEFAULT 'FREE',
  created_at   DATETIME      DEFAULT NOW(),
  is_active    TINYINT(1)    DEFAULT 1,
  url_count    BIGINT        DEFAULT 0,   -- current-month URL count snapshot (synced from Redis every 60s)
  click_count  BIGINT        DEFAULT 0,   -- current-month click count snapshot (synced from Redis every 60s)
  PRIMARY KEY (id),
  UNIQUE KEY uq_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS urls (
  id            BIGINT        NOT NULL,
  tenant_id     BIGINT        NOT NULL,
  short_code    VARCHAR(10)   NOT NULL,
  long_url      TEXT          NOT NULL,
  visibility    ENUM('PUBLIC','PRIVATE') DEFAULT 'PUBLIC',
  password_hash VARCHAR(60),
  expires_at    DATETIME,
  created_at    DATETIME      DEFAULT NOW(),
  created_by    BIGINT,
  is_active     TINYINT(1)    DEFAULT 1,
  PRIMARY KEY (id),
  UNIQUE KEY uq_code (short_code),
  INDEX idx_tenant (tenant_id, created_at),
  INDEX idx_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS analytics_hourly (
  url_id        BIGINT   NOT NULL,
  hour_bucket   DATETIME NOT NULL,
  click_count   BIGINT   DEFAULT 0,
  unique_count  BIGINT   DEFAULT 0,
  PRIMARY KEY (url_id, hour_bucket),
  INDEX idx_url (url_id, hour_bucket DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS url_access_list (
  url_id    BIGINT NOT NULL,
  user_id   BIGINT NOT NULL,
  PRIMARY KEY (url_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO tenants (id, name, email, plan) VALUES (1, 'Default Tenant', 'admin@shortel.io', 'PAID');
