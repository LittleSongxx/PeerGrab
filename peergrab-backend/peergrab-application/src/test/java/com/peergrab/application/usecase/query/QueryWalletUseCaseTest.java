package com.peergrab.application.usecase.query;

import com.peergrab.domain.wallet.ports.WalletQueryPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class QueryWalletUseCaseTest {

    @Test
    void cursor_keeps_full_snowflake_id_and_breaks_same_millisecond_ties() {
        WalletQueryPort port = mock(WalletQueryPort.class);
        QueryWalletUseCase useCase = new QueryWalletUseCase(port);
        Instant sameTime = Instant.parse("2026-09-27T02:03:04.123Z");
        long firstId = 9_223_372_036_854_775_000L;
        long lastId = firstId - 1;
        var first = row(firstId, sameTime);
        var last = row(lastId, sameTime);
        var next = row(lastId - 1, sameTime);
        when(port.ledgerByCursor(eq(1001L), isNull(), isNull(), eq(3)))
                .thenReturn(List.of(first, last, next));
        when(port.ledgerByCursor(eq(1001L), eq(sameTime), eq(lastId), eq(3)))
                .thenReturn(List.of(next));

        CursorPage<WalletQueryPort.LedgerView> page1 = useCase.ledgerByCursor(1001L, "", 2);
        assertEquals(List.of(firstId, lastId), page1.items().stream().map(WalletQueryPort.LedgerView::id).toList());
        assertNotNull(page1.nextCursor());

        CursorPage<WalletQueryPort.LedgerView> page2 = useCase.ledgerByCursor(1001L, page1.nextCursor(), 2);
        assertEquals(List.of(lastId - 1), page2.items().stream().map(WalletQueryPort.LedgerView::id).toList());
        assertNull(page2.nextCursor());
        verify(port).ledgerByCursor(1001L, sameTime, lastId, 3);
    }

    @Test
    void rejects_malformed_cursor() {
        QueryWalletUseCase useCase = new QueryWalletUseCase(mock(WalletQueryPort.class));
        assertThrows(IllegalArgumentException.class, () -> useCase.ledgerByCursor(1L, "invalid!", 20));
    }

    private static WalletQueryPort.LedgerView row(long id, Instant time) {
        return new WalletQueryPort.LedgerView(id, time, "CREDIT", 100,
                "SETTLE", id, "settle:" + id);
    }
}
