-- Run once on existing MySQL volumes during a maintenance window.
-- Fresh volumes already receive these indexes from init.sql.
SET @ledger_user_index_exists = (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE() AND table_name = 'wallet_ledger'
     AND index_name = 'idx_ledger_user_time'
);
SET @ledger_user_index_sql = IF(@ledger_user_index_exists = 0,
  'ALTER TABLE wallet_ledger ADD INDEX idx_ledger_user_time (user_id, created_at, id)',
  'SELECT 1');
PREPARE add_ledger_user_index FROM @ledger_user_index_sql;
EXECUTE add_ledger_user_index;
DEALLOCATE PREPARE add_ledger_user_index;

SET @notification_unread_index_exists = (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE() AND table_name = 'notification'
     AND index_name = 'idx_user_unread'
);
SET @notification_unread_index_sql = IF(@notification_unread_index_exists = 0,
  'ALTER TABLE notification ADD INDEX idx_user_unread (user_id, read_flag)',
  'SELECT 1');
PREPARE add_notification_unread_index FROM @notification_unread_index_sql;
EXECUTE add_notification_unread_index;
DEALLOCATE PREPARE add_notification_unread_index;


-- ==================== 2026-10 修复：保留期清理与 runner 任务列表索引 ====================

SET @grab_runner_errand_index_exists = (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE() AND table_name = 'grab_record'
     AND index_name = 'idx_runner_errand'
);
SET @grab_runner_errand_index_sql = IF(@grab_runner_errand_index_exists = 0,
  'ALTER TABLE grab_record ADD INDEX idx_runner_errand (runner_id, errand_id)',
  'SELECT 1');
PREPARE add_grab_runner_errand_index FROM @grab_runner_errand_index_sql;
EXECUTE add_grab_runner_errand_index;
DEALLOCATE PREPARE add_grab_runner_errand_index;

SET @publish_created_index_exists = (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE() AND table_name = 'publish_request'
     AND index_name = 'idx_publish_created'
);
SET @publish_created_index_sql = IF(@publish_created_index_exists = 0,
  'ALTER TABLE publish_request ADD INDEX idx_publish_created (created_at)',
  'SELECT 1');
PREPARE add_publish_created_index FROM @publish_created_index_sql;
EXECUTE add_publish_created_index;
DEALLOCATE PREPARE add_publish_created_index;

SET @notification_read_time_index_exists = (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE() AND table_name = 'notification'
     AND index_name = 'idx_read_time'
);
SET @notification_read_time_index_sql = IF(@notification_read_time_index_exists = 0,
  'ALTER TABLE notification ADD INDEX idx_read_time (read_flag, created_at)',
  'SELECT 1');
PREPARE add_notification_read_time_index FROM @notification_read_time_index_sql;
EXECUTE add_notification_read_time_index;
DEALLOCATE PREPARE add_notification_read_time_index;

SET @local_message_status_updated_index_exists = (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE() AND table_name = 'local_message'
     AND index_name = 'idx_status_updated'
);
SET @local_message_status_updated_index_sql = IF(@local_message_status_updated_index_exists = 0,
  'ALTER TABLE local_message ADD INDEX idx_status_updated (status, updated_at)',
  'SELECT 1');
PREPARE add_local_message_status_updated_index FROM @local_message_status_updated_index_sql;
EXECUTE add_local_message_status_updated_index;
DEALLOCATE PREPARE add_local_message_status_updated_index;

SET @fund_outbox_status_updated_index_exists = (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE() AND table_name = 'fund_event_outbox'
     AND index_name = 'idx_outbox_status_updated'
);
SET @fund_outbox_status_updated_index_sql = IF(@fund_outbox_status_updated_index_exists = 0,
  'ALTER TABLE fund_event_outbox ADD INDEX idx_outbox_status_updated (status, updated_at)',
  'SELECT 1');
PREPARE add_fund_outbox_status_updated_index FROM @fund_outbox_status_updated_index_sql;
EXECUTE add_fund_outbox_status_updated_index;
DEALLOCATE PREPARE add_fund_outbox_status_updated_index;

-- 2026-10 追加：我的任务按当前 grabber 归属查询（递补/流转后不再错留前一位跑腿）
SET @grabber_created_index_exists = (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE() AND table_name = 'errand'
     AND index_name = 'idx_grabber_created'
);
SET @grabber_created_index_sql = IF(@grabber_created_index_exists = 0,
  'ALTER TABLE errand ADD INDEX idx_grabber_created (grabber_id, created_at)',
  'SELECT 1');
PREPARE add_grabber_created_index FROM @grabber_created_index_sql;
EXECUTE add_grabber_created_index;
DEALLOCATE PREPARE add_grabber_created_index;
