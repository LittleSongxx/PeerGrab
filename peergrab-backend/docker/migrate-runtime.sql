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

SET @autosettle_index_exists = (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE() AND table_name = 'errand' AND index_name = 'idx_autosettle_scan'
);
SET @add_autosettle_sql = IF(@autosettle_index_exists = 0,
  'ALTER TABLE errand ADD INDEX idx_autosettle_scan (status, delivered_at)',
  'SELECT 1');
PREPARE add_autosettle FROM @add_autosettle_sql;
EXECUTE add_autosettle;
DEALLOCATE PREPARE add_autosettle;

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
