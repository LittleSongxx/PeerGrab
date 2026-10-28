-- P12：持久幂等抢单请求（grab_request）升级脚本。
-- 可重复执行；不改写历史表、不删除数据。运行前备份。
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS grab_request (
  id                   BIGINT       NOT NULL PRIMARY KEY,
  campus_id            BIGINT       NOT NULL COMMENT '校区 ID，P6 分片键',
  errand_id            BIGINT       NOT NULL,
  runner_id            BIGINT       NOT NULL,
  operation            VARCHAR(24)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_id           VARCHAR(64)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload_hash         CHAR(64)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_round        INT          NOT NULL,
  base_errand_version  BIGINT       NOT NULL,
  status               VARCHAR(16)  NOT NULL,
  reservation_token    CHAR(36)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  lease_until          DATETIME(3)  NOT NULL,
  result_code          VARCHAR(64)  NULL,
  http_status          SMALLINT     NULL,
  result_json          JSON         NULL,
  created_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  completed_at         DATETIME(3)  NULL,
  UNIQUE KEY uk_grab_request_scope (campus_id, runner_id, operation, request_id),
  KEY idx_grab_request_expiry (status, lease_until, id),
  KEY idx_grab_request_errand (campus_id, errand_id, status),
  CONSTRAINT ck_grab_request_status CHECK (status IN ('PROCESSING','SUCCEEDED','REJECTED','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='抢单请求及永久幂等守卫';

-- 切换前检查：新表首次创建时不应有在途请求。
SELECT COUNT(*) AS processing_requests FROM grab_request WHERE status = 'PROCESSING';
