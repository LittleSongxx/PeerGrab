package com.peergrab.infrastructure.persistence;

import com.peergrab.domain.wallet.ports.FundEventPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 只写 outbox；事务边界由调用方持有，投递由 worker 异步完成。 */
@Component
public class JdbcFundEventOutboxAdapter implements FundEventPort {

    private final JdbcTemplate jdbc;

    public JdbcFundEventOutboxAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(FundEvent event) {
        jdbc.update("""
                INSERT INTO fund_event_outbox
                  (biz_no, event_type, errand_id, publisher_id, runner_id,
                   amount_cents, commission_cents, status, next_retry_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', NOW(3))
                """, event.bizNo(), event.type(), event.errandId(), event.publisherId(),
                event.runnerId(), event.amountCents(), event.commissionCents());
    }
}
