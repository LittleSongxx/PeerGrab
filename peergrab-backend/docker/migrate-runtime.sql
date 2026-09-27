-- 仅用于已创建过数据库卷的旧环境；全新数据库由 init.sql 初始化。
-- MySQL 8 无 ADD COLUMN IF NOT EXISTS，使用 information_schema 保证可重复执行。
SET @delivered_column_exists = (
  SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'errand' AND column_name = 'delivered_at'
);
SET @add_delivered_sql = IF(@delivered_column_exists = 0,
  'ALTER TABLE errand ADD COLUMN delivered_at DATETIME(3) NULL AFTER locked_at',
  'SELECT 1');
PREPARE add_delivered FROM @add_delivered_sql;
EXECUTE add_delivered;
DEALLOCATE PREPARE add_delivered;

-- 截止时间一经写入便不受后续配置变化影响。旧数据按默认窗口回填；若旧部署
-- 使用了自定义窗口，应在执行前覆盖下方两个会话变量。
SET @peergrab_confirm_seconds = COALESCE(@peergrab_confirm_seconds, 300);
SET @peergrab_auto_seconds = COALESCE(@peergrab_auto_seconds, 86400);
SET @confirm_deadline_column_exists = (
  SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'errand' AND column_name = 'confirm_deadline_at'
);
SET @add_confirm_deadline_sql = IF(@confirm_deadline_column_exists = 0,
  'ALTER TABLE errand ADD COLUMN confirm_deadline_at DATETIME(3) NULL AFTER locked_at', 'SELECT 1');
PREPARE add_confirm_deadline FROM @add_confirm_deadline_sql;
EXECUTE add_confirm_deadline;
DEALLOCATE PREPARE add_confirm_deadline;

SET @auto_deadline_column_exists = (
  SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'errand' AND column_name = 'auto_settle_deadline_at'
);
SET @add_auto_deadline_sql = IF(@auto_deadline_column_exists = 0,
  'ALTER TABLE errand ADD COLUMN auto_settle_deadline_at DATETIME(3) NULL AFTER delivered_at', 'SELECT 1');
PREPARE add_auto_deadline FROM @add_auto_deadline_sql;
EXECUTE add_auto_deadline;
DEALLOCATE PREPARE add_auto_deadline;

-- 很早的旧库可能只有状态而无对应时间。先取最后一次状态日志时间；
-- updated_at 更晚时采用它，宁可延后处理也不根据不可靠时间提前流转或结算。
UPDATE errand e
   SET e.locked_at = GREATEST(e.updated_at,
       COALESCE((SELECT MAX(l.created_at) FROM errand_status_log l
                  WHERE l.errand_id = e.id AND l.to_status = 'LOCKED'), e.updated_at))
 WHERE e.status = 'LOCKED' AND e.locked_at IS NULL;
UPDATE errand e
   SET e.delivered_at = GREATEST(e.updated_at,
       COALESCE((SELECT MAX(l.created_at) FROM errand_status_log l
                  WHERE l.errand_id = e.id AND l.to_status = 'DELIVERED'), e.updated_at))
 WHERE e.status = 'DELIVERED' AND e.delivered_at IS NULL;

UPDATE errand SET confirm_deadline_at = DATE_ADD(locked_at, INTERVAL @peergrab_confirm_seconds SECOND)
 WHERE status = 'LOCKED' AND locked_at IS NOT NULL AND confirm_deadline_at IS NULL;
UPDATE errand SET auto_settle_deadline_at = DATE_ADD(delivered_at, INTERVAL @peergrab_auto_seconds SECOND)
 WHERE status = 'DELIVERED' AND delivered_at IS NOT NULL AND auto_settle_deadline_at IS NULL;

SET @confirm_deadline_index_exists = (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE() AND table_name = 'errand' AND index_name = 'idx_confirm_deadline'
);
SET @add_confirm_deadline_index_sql = IF(@confirm_deadline_index_exists = 0,
  'ALTER TABLE errand ADD INDEX idx_confirm_deadline (status, confirm_deadline_at, id)', 'SELECT 1');
PREPARE add_confirm_deadline_index FROM @add_confirm_deadline_index_sql;
EXECUTE add_confirm_deadline_index;
DEALLOCATE PREPARE add_confirm_deadline_index;

SET @auto_deadline_index_exists = (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE() AND table_name = 'errand' AND index_name = 'idx_auto_settle_deadline'
);
SET @add_auto_deadline_index_sql = IF(@auto_deadline_index_exists = 0,
  'ALTER TABLE errand ADD INDEX idx_auto_settle_deadline (status, auto_settle_deadline_at, id)', 'SELECT 1');
PREPARE add_auto_deadline_index FROM @add_auto_deadline_index_sql;
EXECUTE add_auto_deadline_index;
DEALLOCATE PREPARE add_auto_deadline_index;

-- 新截止时间索引替代旧的起始时间索引，减少每次任务状态更新的索引维护。
SET @old_timeout_index_exists = (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE() AND table_name = 'errand' AND index_name = 'idx_timeout_scan'
);
SET @drop_old_timeout_sql = IF(@old_timeout_index_exists > 0,
  'ALTER TABLE errand DROP INDEX idx_timeout_scan', 'SELECT 1');
PREPARE drop_old_timeout FROM @drop_old_timeout_sql;
EXECUTE drop_old_timeout;
DEALLOCATE PREPARE drop_old_timeout;

SET @old_autosettle_index_exists = (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE() AND table_name = 'errand' AND index_name = 'idx_autosettle_scan'
);
SET @drop_old_autosettle_sql = IF(@old_autosettle_index_exists > 0,
  'ALTER TABLE errand DROP INDEX idx_autosettle_scan', 'SELECT 1');
PREPARE drop_old_autosettle FROM @drop_old_autosettle_sql;
EXECUTE drop_old_autosettle;
DEALLOCATE PREPARE drop_old_autosettle;

INSERT INTO wallet_account (id, owner_id, owner_type, available, frozen, version) VALUES
  (2001, 2001, 'USER', 5000, 0, 0),
  (2002, 2002, 'USER', 5000, 0, 0)
ON DUPLICATE KEY UPDATE id = id;

-- 抢单高频资格查询：WHERE grabber_id=? AND status IN (...)。
SET @grabber_status_index_exists = (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE() AND table_name = 'errand' AND index_name = 'idx_grabber_status'
);
SET @add_grabber_status_sql = IF(@grabber_status_index_exists = 0,
  'ALTER TABLE errand ADD INDEX idx_grabber_status (grabber_id, status)',
  'SELECT 1');
PREPARE add_grabber_status FROM @add_grabber_status_sql;
EXECUTE add_grabber_status;
DEALLOCATE PREPARE add_grabber_status;

-- 旧抢单记录没有 request_id，MySQL UNIQUE 允许历史多条 NULL；新记录由应用写入非空。
SET @grab_request_column_exists = (
  SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'grab_record' AND column_name = 'request_id'
);
SET @add_grab_request_sql = IF(@grab_request_column_exists = 0,
  'ALTER TABLE grab_record ADD COLUMN request_id VARCHAR(128) NULL COMMENT ''抢单请求幂等键；旧记录允许为空'' AFTER runner_id',
  'SELECT 1');
PREPARE add_grab_request FROM @add_grab_request_sql;
EXECUTE add_grab_request;
DEALLOCATE PREPARE add_grab_request;

SET @grab_request_index_exists = (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE() AND table_name = 'grab_record' AND index_name = 'uk_errand_request'
);
SET @add_grab_request_index_sql = IF(@grab_request_index_exists = 0,
  'ALTER TABLE grab_record ADD UNIQUE INDEX uk_errand_request (errand_id, request_id)',
  'SELECT 1');
PREPARE add_grab_request_index FROM @add_grab_request_index_sql;
EXECUTE add_grab_request_index;
DEALLOCATE PREPARE add_grab_request_index;

-- 账本按账户版本审计，新流水写入非空 account_version；旧流水不猜测历史顺序。
SET @ledger_version_column_exists = (
  SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'wallet_ledger' AND column_name = 'account_version'
);
SET @add_ledger_version_sql = IF(@ledger_version_column_exists = 0,
  'ALTER TABLE wallet_ledger ADD COLUMN account_version BIGINT NULL COMMENT ''该账户本笔流水后的版本；历史记录可为空'' AFTER balance_after',
  'SELECT 1');
PREPARE add_ledger_version FROM @add_ledger_version_sql;
EXECUTE add_ledger_version;
DEALLOCATE PREPARE add_ledger_version;

SET @ledger_version_index_exists = (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE() AND table_name = 'wallet_ledger' AND index_name = 'uk_account_version'
);
SET @add_ledger_version_index_sql = IF(@ledger_version_index_exists = 0,
  'ALTER TABLE wallet_ledger ADD UNIQUE INDEX uk_account_version (account_id, account_version)',
  'SELECT 1');
PREPARE add_ledger_version_index FROM @add_ledger_version_index_sql;
EXECUTE add_ledger_version_index;
DEALLOCATE PREPARE add_ledger_version_index;

-- 旧版本把定时消息的下次发送时间放在到期日，导致自动结算 MQ 通道从未提前持有定时消息。
-- 新版本应尽快把 PENDING 消息发送给 broker；deliver_at 仍是实际投递时间。
UPDATE local_message
   SET next_retry_at = NOW(3)
 WHERE status = 'PENDING' AND retry_count = 0 AND next_retry_at > NOW(3);

-- 发布重试键与任务、资金变更同事务提交；旧任务无请求键，不作不可靠回填。
CREATE TABLE IF NOT EXISTS publish_request (
  publisher_id BIGINT NOT NULL,
  request_id   VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  payload_hash CHAR(64) NOT NULL,
  errand_id    BIGINT NULL,
  created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (publisher_id, request_id),
  UNIQUE KEY uk_publish_errand (errand_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发布请求幂等登记';

-- 旧环境先执行本迁移，再部署使用资金事件 outbox 的应用和 worker。
CREATE TABLE IF NOT EXISTS fund_event_outbox (
  biz_no           VARCHAR(64)  NOT NULL COMMENT '账本业务幂等号，同时作为 MQ 消息 key',
  event_type       VARCHAR(32)  NOT NULL COMMENT 'SETTLED/REFUNDED/ARBITRATED',
  errand_id        BIGINT       NOT NULL,
  publisher_id     BIGINT       NOT NULL,
  runner_id        BIGINT       NOT NULL,
  amount_cents     BIGINT       NOT NULL,
  commission_cents BIGINT       NOT NULL,
  status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENT',
  retry_count      INT          NOT NULL DEFAULT 0,
  next_retry_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  claim_token      CHAR(36)     NULL COMMENT '当前发送者的令牌',
  claim_until      DATETIME(3) NULL COMMENT '发送租约到期时间',
  created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (biz_no),
  KEY idx_fund_outbox_pending (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资金事件持久投递箱';

-- 旧库给两种 outbox 增加领取租约。旧 PENDING 行仍可被下一轮 Worker 领取。
SET @local_claim_token_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'local_message' AND column_name = 'claim_token');
SET @local_claim_token_sql = IF(@local_claim_token_exists = 0,
  'ALTER TABLE local_message ADD COLUMN claim_token CHAR(36) NULL', 'SELECT 1');
PREPARE local_claim_token_stmt FROM @local_claim_token_sql;
EXECUTE local_claim_token_stmt;
DEALLOCATE PREPARE local_claim_token_stmt;

SET @local_claim_until_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'local_message' AND column_name = 'claim_until');
SET @local_claim_until_sql = IF(@local_claim_until_exists = 0,
  'ALTER TABLE local_message ADD COLUMN claim_until DATETIME(3) NULL', 'SELECT 1');
PREPARE local_claim_until_stmt FROM @local_claim_until_sql;
EXECUTE local_claim_until_stmt;
DEALLOCATE PREPARE local_claim_until_stmt;

SET @fund_claim_token_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'fund_event_outbox' AND column_name = 'claim_token');
SET @fund_claim_token_sql = IF(@fund_claim_token_exists = 0,
  'ALTER TABLE fund_event_outbox ADD COLUMN claim_token CHAR(36) NULL', 'SELECT 1');
PREPARE fund_claim_token_stmt FROM @fund_claim_token_sql;
EXECUTE fund_claim_token_stmt;
DEALLOCATE PREPARE fund_claim_token_stmt;

SET @fund_claim_until_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'fund_event_outbox' AND column_name = 'claim_until');
SET @fund_claim_until_sql = IF(@fund_claim_until_exists = 0,
  'ALTER TABLE fund_event_outbox ADD COLUMN claim_until DATETIME(3) NULL', 'SELECT 1');
PREPARE fund_claim_until_stmt FROM @fund_claim_until_sql;
EXECUTE fund_claim_until_stmt;
DEALLOCATE PREPARE fund_claim_until_stmt;

CREATE TABLE IF NOT EXISTS snowflake_node_lease (
  node_id       SMALLINT UNSIGNED NOT NULL,
  holder        CHAR(36)          NOT NULL,
  expires_at    DATETIME(3)      NOT NULL,
  safe_after_ms BIGINT           NOT NULL COMMENT '下一持有者发号前必须越过的毫秒上界',
  updated_at    DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (node_id),
  KEY idx_snowflake_expiry (expires_at),
  CONSTRAINT ck_snowflake_node_id CHECK (node_id <= 1023)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='雪花节点租约与时间栅栏';

-- 抢中与候选递补的事务内额度锁；旧任务按 errand 状态实时计数，无需回填。
CREATE TABLE IF NOT EXISTS runner_quota_lock (
  runner_id BIGINT NOT NULL,
  PRIMARY KEY (runner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跑腿在途额度事务锁';

-- 切换生产鉴权到 JWT 前执行。旧 Redis Session/refresh 无法安全迁入，用户须重新登录。
CREATE TABLE IF NOT EXISTS auth_token_session (
  session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id BIGINT NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  revoked_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (session_id),
  KEY idx_auth_session_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='JWT 会话有效性真值';

CREATE TABLE IF NOT EXISTS auth_refresh_token (
  token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'SHA-256 十六进制摘要',
  session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (token_hash),
  UNIQUE KEY uk_auth_refresh_session (session_id),
  KEY idx_auth_refresh_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='一次性 refresh token 哈希';

-- 扫描游标越过失败任务前先持久登记；进程重启后仍可重试。
CREATE TABLE IF NOT EXISTS worker_scan_retry (
  job_type      VARCHAR(24)  NOT NULL COMMENT 'CONFIRM_TIMEOUT/AUTO_SETTLE',
  errand_id     BIGINT       NOT NULL,
  round         INT          NOT NULL DEFAULT 0 COMMENT '超时流转轮次；自动结算固定为 0',
  attempts      INT          NOT NULL DEFAULT 1,
  next_retry_at DATETIME(3)  NOT NULL,
  lease_token   CHAR(36)     NULL,
  lease_until   DATETIME(3)  NULL,
  last_error    VARCHAR(255) NULL,
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (job_type, errand_id),
  KEY idx_scan_retry_due (job_type, next_retry_at, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Worker 扫描失败持久重试';
