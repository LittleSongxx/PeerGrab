package com.peergrab.application.usecase.query;

import com.peergrab.domain.wallet.ports.WalletQueryPort;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/** 钱包查询：余额 + 流水分页 */
@Service
public class QueryWalletUseCase {

    private final WalletQueryPort queryPort;

    public QueryWalletUseCase(WalletQueryPort queryPort) {
        this.queryPort = queryPort;
    }

    public WalletQueryPort.BalanceView balance(long userId) {
        return queryPort.findBalance(userId)
                .orElse(new WalletQueryPort.BalanceView(0, 0));
    }

    public List<WalletQueryPort.LedgerView> ledger(long userId, int page, int size) {
        return queryPort.ledger(userId, Math.max(page, 0), Math.min(Math.max(size, 1), 50));
    }

    public CursorPage<WalletQueryPort.LedgerView> ledgerByCursor(long userId, String cursor, int size) {
        int normalizedSize = Math.min(Math.max(size, 1), 50);
        LedgerCursor before = decode(cursor);
        List<WalletQueryPort.LedgerView> rows = queryPort.ledgerByCursor(
                userId, before.createdAt(), before.id(), normalizedSize + 1);
        boolean hasNext = rows.size() > normalizedSize;
        List<WalletQueryPort.LedgerView> page = hasNext ? rows.subList(0, normalizedSize) : rows;
        String nextCursor = hasNext ? encode(page.get(page.size() - 1)) : null;
        return new CursorPage<>(page, nextCursor);
    }

    private LedgerCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return new LedgerCursor(null, null);
        if (cursor.length() > 128) throw new IllegalArgumentException("非法流水游标");
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split(":", 2);
            if (parts.length != 2) throw new IllegalArgumentException("非法流水游标");
            return new LedgerCursor(Instant.ofEpochMilli(Long.parseLong(parts[0])), Long.parseLong(parts[1]));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("非法流水游标", e);
        }
    }

    private String encode(WalletQueryPort.LedgerView last) {
        // DATETIME(3) 与 epoch 毫秒精度相同；ID 全程使用十进制文本，避免 JS 精度截断。
        String raw = last.time().toEpochMilli() + ":" + last.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private record LedgerCursor(Instant createdAt, Long id) {}
}
