package com.peergrab.infrastructure.persistence;

import com.peergrab.domain.wallet.ports.FundEventPort;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JdbcFundEventOutboxAdapterTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final JdbcFundEventOutboxAdapter adapter = new JdbcFundEventOutboxAdapter(jdbc);
    private final FundEventPort.FundEvent event = new FundEventPort.FundEvent(
            "settle:41", "SETTLED", 41, 1001, 2001, 950, 50);

    @Test
    void appendMapsEventToSingleOutboxInsert() {
        adapter.append(event);

        verify(jdbc).update(contains("INSERT INTO fund_event_outbox"),
                eq("settle:41"), eq("SETTLED"), eq(41L), eq(1001L), eq(2001L), eq(950L), eq(50L));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void appendPropagatesDatabaseFailureToOwningTransaction() {
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("db unavailable"));

        assertThrows(DataAccessResourceFailureException.class, () -> adapter.append(event));
    }
}
