-- 将旧的共享 ESCROW(-1) 账户迁移为“每单托管户/佣金户”。
-- 仅用于已有数据卷；全新库由 init.sql 初始化（不再预置 -1/-2 系统户）。
-- 迁移后：每个 HELD 托管单有 owner_id=errand_id 的 ESCROW/COMMISSION 账户；
-- 共享 -1 ESCROW 的余额转到各任务托管户并归零；旧 -1/-2 行保留作历史流水归属。
-- 幂等：已存在每单 ESCROW 账户（或没有 HELD 单）时重复执行为 no-op。
-- 执行前必须停 app/worker 并备份；建议在恢复副本上先演练。

DROP PROCEDURE IF EXISTS migrate_wallet_per_errand;

DELIMITER $$

CREATE PROCEDURE migrate_wallet_per_errand()
BEGIN
    DECLARE v_held_count INT DEFAULT 0;
    DECLARE v_partial_count INT DEFAULT 0;
    DECLARE v_held_sum BIGINT DEFAULT 0;
    DECLARE v_shared_id BIGINT DEFAULT NULL;
    DECLARE v_shared_available BIGINT DEFAULT 0;
    DECLARE v_shared_frozen BIGINT DEFAULT 0;
    DECLARE v_shared_version BIGINT DEFAULT 0;

    SELECT COUNT(*), COALESCE(SUM(e.amount), 0)
      INTO v_held_count, v_held_sum
      FROM escrow_order e
     WHERE e.status = 'HELD'
       AND NOT EXISTS (SELECT 1 FROM wallet_account w
                        WHERE w.owner_id = e.errand_id AND w.owner_type = 'ESCROW')
       AND NOT EXISTS (SELECT 1 FROM wallet_account w
                        WHERE w.owner_id = e.errand_id AND w.owner_type = 'COMMISSION');

    SELECT COUNT(*)
      INTO v_partial_count
      FROM escrow_order e
     WHERE e.status = 'HELD'
       AND ((EXISTS (SELECT 1 FROM wallet_account w
                      WHERE w.owner_id = e.errand_id AND w.owner_type = 'ESCROW'))
            <> (EXISTS (SELECT 1 FROM wallet_account w
                         WHERE w.owner_id = e.errand_id AND w.owner_type = 'COMMISSION')));

    IF v_partial_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'wallet migration aborted: HELD escrow has only one of ESCROW/COMMISSION accounts';
    ELSEIF v_held_count = 0 THEN
        SELECT 'wallet migration: no HELD escrow candidates, no-op' AS result;
    ELSE
        SELECT id, available, frozen, version
          INTO v_shared_id, v_shared_available, v_shared_frozen, v_shared_version
          FROM wallet_account
         WHERE owner_id = -1 AND owner_type = 'ESCROW'
         LIMIT 1;

        IF v_shared_id IS NULL OR v_shared_frozen <> 0 OR v_shared_available <> v_held_sum THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'wallet migration aborted: shared ESCROW balance/frozen does not match HELD escrow sum';
        END IF;

        DROP TEMPORARY TABLE IF EXISTS tmp_wallet_migration;
        CREATE TEMPORARY TABLE tmp_wallet_migration (
            seq                   INT NOT NULL,
            errand_id             BIGINT NOT NULL PRIMARY KEY,
            publisher_id          BIGINT NOT NULL,
            amount                BIGINT NOT NULL,
            escrow_account_id     BIGINT NOT NULL,
            commission_account_id BIGINT NOT NULL,
            shared_balance_after  BIGINT NOT NULL,
            shared_account_version BIGINT NOT NULL
        ) ENGINE=InnoDB;

        START TRANSACTION;

        INSERT INTO tmp_wallet_migration
            (seq, errand_id, publisher_id, amount, escrow_account_id,
             commission_account_id, shared_balance_after, shared_account_version)
        SELECT seq, errand_id, publisher_id, amount,
               -(1000000000000 + seq * 2 - 1),
               -(1000000000000 + seq * 2),
               v_shared_available - SUM(amount) OVER (ORDER BY errand_id),
               v_shared_version + seq
          FROM (
                SELECT ROW_NUMBER() OVER (ORDER BY e.errand_id) AS seq,
                       e.errand_id, e.publisher_id, e.amount
                  FROM escrow_order e
                 WHERE e.status = 'HELD'
                   AND NOT EXISTS (SELECT 1 FROM wallet_account w
                                    WHERE w.owner_id = e.errand_id AND w.owner_type = 'ESCROW')
                   AND NOT EXISTS (SELECT 1 FROM wallet_account w
                                    WHERE w.owner_id = e.errand_id AND w.owner_type = 'COMMISSION')
               ) candidates;

        INSERT INTO wallet_account (id, owner_id, owner_type, available, frozen, version)
        SELECT escrow_account_id, errand_id, 'ESCROW', amount, 0, 1
          FROM tmp_wallet_migration;

        INSERT INTO wallet_account (id, owner_id, owner_type, available, frozen, version)
        SELECT commission_account_id, errand_id, 'COMMISSION', 0, 0, 0
          FROM tmp_wallet_migration;

        -- 共享托管户 DEBIT：余额按迁移顺序递减，版本连续递增。
        INSERT INTO wallet_ledger
            (id, biz_no, account_id, user_id, direction, amount, balance_after,
             account_version, ref_type, ref_id)
        SELECT -(2000000000000 + seq * 2 - 1),
               CONCAT('wallet-migrate:', errand_id),
               v_shared_id,
               publisher_id,
               'DEBIT',
               amount,
               shared_balance_after,
               shared_account_version,
               'ESCROW',
               errand_id
          FROM tmp_wallet_migration;

        -- 每单托管户 CREDIT：账户初始 version=1，流水版本也必须是 1。
        INSERT INTO wallet_ledger
            (id, biz_no, account_id, user_id, direction, amount, balance_after,
             account_version, ref_type, ref_id)
        SELECT -(2000000000000 + seq * 2),
               CONCAT('wallet-migrate:', errand_id),
               escrow_account_id,
               publisher_id,
               'CREDIT',
               amount,
               amount,
               1,
               'ESCROW',
               errand_id
          FROM tmp_wallet_migration;

        UPDATE wallet_account
           SET available = available - v_held_sum,
               version = version + v_held_count
         WHERE id = v_shared_id;

        COMMIT;

        SELECT CONCAT('wallet migration: migrated ', v_held_count, ' HELD escrow(s), sum=', v_held_sum) AS result;
        DROP TEMPORARY TABLE IF EXISTS tmp_wallet_migration;
    END IF;
END$$

DELIMITER ;

CALL migrate_wallet_per_errand();
DROP PROCEDURE IF EXISTS migrate_wallet_per_errand;
