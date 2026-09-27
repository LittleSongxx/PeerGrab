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
